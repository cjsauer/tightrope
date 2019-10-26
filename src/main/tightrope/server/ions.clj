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
;; Built-in schema

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

(defn ensure-schemas
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
          :where [?c :aws.apigw.ws.conn/id ?cid]]
        db)
   (mapv peek)))

(defn save-conn-id!
  [conn conn-id]
  (d/transact conn {:tx-data [{:aws.apigw.ws.conn/id conn-id}]}))

(defn retract-conn-id!
  [conn conn-id]
  (d/transact conn {:tx-data [[:db/retractEntity [:aws.apigw.ws.conn/id conn-id]]]}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API Gateway Handler

(defn ion-handler
  [config]
  (let [conn           (get-conn config)
        env            {:conn conn}
        plugins        [(pcd/datomic-connect-plugin (assoc client-config ::pcd/conn conn))]
        handler-config (select-keys config [:path :parser :parser-opts])
        merged-config  (-> handler-config
                           (update-in [:parser-opts :env] merge env)
                           (update-in [:parser-opts :plugins] (fnil concat []) plugins))]
    (handler/http-handler merged-config)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket sending

(defn- encode-data
  [data]
  ;; TODO encode this with something other than str
  (-> data pr-str))

(defn- retract-connection!
  [conn conn-id]
  (d/transact conn {:tx-data [[:db/retractEntity [:aws.apigw.ws.conn/id conn-id]]]}))

(defn- make-client
  [config]
  (.. (ApiGatewayManagementApiAsyncClient/builder)
      (endpointOverride (-> config :remote :ws-uri URI.))
      (build)))

(defn- make-request
  [conn-id data]
  (let [data-bytes (-> data encode-data .getBytes SdkBytes/fromByteArray)]
    (.. (PostToConnectionRequest/builder)
        (connectionId conn-id)
        (data data-bytes)
        (build))))

(defn send-data!
  [config data & conn-ids]
  (let [client (make-client config)]
    (doseq [cid conn-ids]
      (let [request (make-request cid data)]
        (try
          (.postToConnection client request)
          (catch Exception e nil))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Broadcasting

;; Instead of multiplexing based on connections, broadcast _all_ datoms
;; to _all_ connections, subject to an authorization (authz) function.
;; This function is user-provided.
;;
;; Signature of authz:

(defn authorize
  [config datom]
  ((:authz config) config datom))

(defn- authorization-plan
  [])


(defn eid->lookups
  [db eid]
  (d/q '[:find ?ident ?val
         :in $ ?e
         :where
         [?attr :db/unique :db.unique/identity]
         [?attr :db/ident ?ident]
         [?e ?attr ?val]]
       db eid))

(defn- ref?
  [db a]
  (-> (d/pull db [:db/valueType] a)
      :db/valueType
      (= :db.type/ref)))

(defn- lookup-table-entry
  [db [e a v]]
  (let [ents (if (ref? db a)
               [e v]
               [e])]
    (into #{}
          (mapcat (partial eid->lookups db))
          ents)))

(defn- make-lookup-table
  [db datoms]
  (reduce (fn [table [e a v :as datom]]
            (let [lkups (lookup-table-entry db datom)]
              (cond-> table
                (not-empty lkups)
                (assoc e lkups))))
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
  (let [txd (d/transact (get-conn config) {:tx-data tx-data})]
    (broadcast-tx-result! config txd)
    txd))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Connection management

(defn on-connect
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketConnectEvent" ::input input})
    (save-conn-id! (get-conn config) conn-id)
    {:status 200
     :body   "connected"}))

(defn on-disconnect
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketDisconnectEvent" ::input input})
    (retract-conn-id! (get-conn config) conn-id)
    {:status 200
     :body   "disconnected"}))

(defn on-message
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketMessageEvent" ::input input})
    {:status 200
     :body   (encode-data {:conn-id conn-id})}))
