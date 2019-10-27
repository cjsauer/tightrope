(ns tightrope.server.ions
  (:require [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.connect.datomic :as pcd]
            [com.wsscode.pathom.connect.datomic.client :refer [client-config]]
            [datomic.client.api :as d]
            [datomic.ion.cast :as icast]
            [datomic.ion.edn.api-gateway :as edngw]
            [tightrope.server.handler :as handler])
  (:import java.net.URI
           software.amazon.awssdk.core.SdkBytes
           software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiAsyncClient
           software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Built-ins

(def ws-connection-schema
  [{:db/ident       :aws.apigw.ws.conn/id
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory   true
    :db/doc         "API Gateway websocket connection ID"}
   ])

(def built-in-schemas
  [ws-connection-schema])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database helpers

(def get-client
  (memoize #(d/client %)))

(defn- has-ident?
  [db ident]
  (contains? (d/pull db {:eid ident :selector [:db/ident]})
             :db/ident))

(defn- schema-loaded?
  [db schema]
  (has-ident? db (-> schema first :db/ident)))

(defn- load-schemas
  [conn schemas]
  (let [db (d/db conn)]
    (doseq [sch schemas]
      (when-not (schema-loaded? db sch)
        (d/transact conn {:tx-data sch})))))

(defn- ensure-schemas
  [{:keys [db-name schemas] :as config}]
  (let [client (get-client (:datomic-config config))]
    (d/create-database client {:db-name db-name})
    (let [conn        (d/connect client {:db-name db-name})
          db          (d/db conn)
          all-schemas (concat built-in-schemas schemas)]
      (load-schemas conn all-schemas)
      conn)))

(defn get-conn
  [config]
  (ensure-schemas config))

(defn get-db
  [config]
  (d/db (get-conn config)))

(defn all-conn-ids
  [db]
  (->>
   (d/q '[:find ?cid
          :in $
          :where [_ :aws.apigw.ws.conn/id ?cid]]
        db)
   (mapv peek)))

(defn- save-user-conn-id!
  [conn user-lookup conn-id]
  (d/transact conn {:tx-data [(conj {:aws.apigw.ws.conn/id conn-id}
                                    user-lookup)]}))

(defn- retract-user-conn-id!
  [conn conn-id]
  (when-let [eid (-> conn
                     d/db
                     (d/pull [:db/id] [:aws.apigw.ws.conn/id conn-id])
                     :db/id)]
    (d/transact conn {:tx-data [[:db/retract eid :aws.apigw.ws.conn/id conn-id]]})))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket sending

(defn- encode-data
  [data]
  ;; TODO encode this with something other than str
  (-> data pr-str))

(defn- make-client
  [config]
  (.. (ApiGatewayManagementApiAsyncClient/builder)
      (endpointOverride (-> config :remote :ws-uri URI.))
      (build)))

(defn- make-request
  [conn-id encoded-data]
  (let [data-bytes (-> encoded-data .getBytes SdkBytes/fromByteArray)]
    (.. (PostToConnectionRequest/builder)
        (connectionId conn-id)
        (data data-bytes)
        (build))))

(defn send-data!
  [config data & conn-ids]
  (let [client       (make-client config)
        encoded-data (encode-data data)
        conn         (get-conn config)]
    (doseq [cid conn-ids]
      (let [request (make-request cid encoded-data)]
        (-> (.postToConnection client request)
            (.exceptionally (reify java.util.function.Function
                              (apply [this _]
                                (retract-user-conn-id! conn cid)))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Datom authorization

;; (defn authorize
;;   [config datom]
;;   ((:authz config) config datom))

;; (defn- authorization-plan
;;   [])



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Datom normalization

(defn eid->lookups
  [db eid]
  (->> (d/q '[:find ?ident ?val
              :in $ ?e
              :where
              [?attr :db/unique :db.unique/identity]
              [?attr :db/ident ?ident]
              [?e ?attr ?val]]
            db eid)
       (into #{})))

(defn- ref?
  [db a]
  (-> (d/pull db [{:db/valueType [:db/ident]}] a)
      :db/valueType
      :db/ident
      (= :db.type/ref)))

(defn- lookup-table-entry
  [db [e a v]]
  (let [e-lkups (eid->lookups db e)]
   (cond-> {}
     (not-empty e-lkups)
     (assoc e e-lkups)
     (ref? db a)
     (assoc v (eid->lookups db v)))))

(defn- make-lookup-table
  [db datoms]
  (reduce (fn [table [e a v :as datom]]
            (let [entry (lookup-table-entry db datom)]
              (cond->> table
                (not-empty entry)
                (merge-with conj entry))))
          {}
          (map (juxt :e :a :v) datoms)))

(defn- normalize-datom
  [db datom]
  (let [attr (d/pull db [:db/ident] (:a datom))]
    [(:e datom)
     (:db/ident attr)
     (:v datom)
     (:tx datom)
     (:added datom)]))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Datom broadcasting

(defn- broadcast-datoms!
  [config db datoms]
  (let [conn-ids    (all-conn-ids db)
        table       (make-lookup-table db datoms)
        norm-datoms (mapv (partial normalize-datom db) datoms)
        data        {:datoms       norm-datoms
                     :eid->lookups table}]
    (when-not (empty? table)
      (apply send-data! config data conn-ids))))

(defn- broadcast-tx-result!
  [config tx-data]
  (broadcast-datoms! config (:db-after tx-data) (:tx-data tx-data)))

(defn xact!
  [config tx-data]
  (let [tx-res (d/transact (get-conn config) {:tx-data tx-data})]
    (broadcast-tx-result! config tx-res)
    tx-res))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket connection management

(pc/defmutation complete-handshake!
  [{:keys [request conn config]} {:aws.apigw.ws.conn/keys [id]}]
  {::pc/input #{:aws.apigw.ws.conn/id}}
  (when-let [user-lookup ((-> config :remote :request->lookup) request)]
    (save-user-conn-id! conn user-lookup id)
    nil))

(defn on-connect
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketConnectEvent" ::input input})
    {:status 200
     :body   "connected"}))

(defn on-disconnect
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketDisconnectEvent" ::input input})
    (retract-user-conn-id! (get-conn config) conn-id)
    {:status 200
     :body   "disconnected"}))

(defn on-message
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketMessageEvent" ::input input})
    {:status 200
     :body   (encode-data {:conn-id conn-id})}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Primary API handler

(def built-in-resolvers
  [complete-handshake!])

(defn ion-handler
  [config]
  (let [conn           (get-conn config)
        env            {:conn   conn
                        :config config}
        plugins        [(pcd/datomic-connect-plugin (assoc client-config ::pcd/conn conn))]
        merged-config  (-> config
                           (update-in [:parser-opts :env] merge env)
                           (update-in [:parser-opts :plugins] (fnil concat []) plugins)
                           (update-in [:parser-opts :resolvers] (fnil concat []) built-in-resolvers))]
    (handler/http-handler merged-config)))
