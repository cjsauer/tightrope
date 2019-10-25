(ns tightrope.server.ions
  (:require [com.wsscode.pathom.connect.datomic :as pcd]
            [com.wsscode.pathom.connect.datomic.client :refer [client-config]]
            [datomic.client.api :as d]
            [tightrope.server.handler :as handler]
            [datomic.ion.cast :as icast]
            [datomic.ion.lambda.api-gateway :as apigw]
            [datomic.ion.edn.api-gateway :as edngw]
            )
  (:import [java.net URI]
           [software.amazon.awssdk.services.apigatewaymanagementapi
            ApiGatewayManagementApiClient
            ApiGatewayManagementApiAsyncClient
            ApiGatewayManagementApiAsyncClientBuilder]
           [software.amazon.awssdk.services.apigatewaymanagementapi.model
            GetConnectionRequest
            PostToConnectionRequest
            PostToConnectionResponse]
           [software.amazon.awssdk.core SdkBytes]
           ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Built-in schema

(def ws-connection-schema
  [{:db/ident       :aws.apigw.ws.connection/id
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory   true
    :db/doc         "API Gateway websocket connection ID"}

   {:db/ident       ::watch
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/noHistory   true
    :db/doc         "Signals an API Gateway WS connection's intent to watch this entity"}])

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
;; Entity subscription

(defn subscribe!
  [conn connId lookups]
  (d/transact conn {:tx-data [{:aws.apigw.ws.connection/id connId
                               ::watch lookups}]}))

(defn unsubscribe-tx
  [db connId lookups]
  (mapv #(vector :db/retract (first %) ::watch (second %))
        (d/q '[:find ?conn ?watched
               :in $ ?cid [[?ident-attr ?ident] ...]
               :where
               [?conn :aws.apigw.ws.connection/id ?cid]
               [?conn ::watch ?watched]
               [?watched ?ident-attr ?ident]]
             db connId lookups)))

(defn unsubscribe!
  [conn connId lookups]
  (let [retractions (unsubscribe-tx (d/db conn) connId lookups)]
    (d/transact conn {:tx-data retractions})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Datom multiplexing

(defn multiplex-datoms
  [db tx-data]
  (->> (d/q '[:find ?cid ?e ?attr ?v ?tx ?op
              :in $ [[?e ?a ?v ?tx ?op] ...]
              :where
              [?conn ::watch ?e]
              [?conn :aws.apigw.ws.connection/id ?cid]
              [?a :db/ident ?attr]]
            db
            (map (juxt :e :a :v :tx :added) tx-data))
       (reduce (fn [plan cid-datom]
                 (update plan (first cid-datom) (fnil conj #{}) (subvec cid-datom 1)))
               {})))

;; plan looks like:
;;
;; {"c1" [[15256823347019860 :user/age 28 13194139533332 true]
;;        [39424088925536341 :user/age 26 13194139533332 true]]
;;  "c2" [...]}

(defn eid->lookups
  [db eid]
  (d/q '[:find ?ident ?val
         :in $ ?e
         :where
         [?attr :db/unique :db.unique/identity]
         [?attr :db/ident ?ident]
         [?e ?attr ?val]]
       db eid))

(defn make-lookup-table
  [db datoms]
  (reduce (fn [table e]
            (assoc table e (eid->lookups db e)))
          {}
          (map :e datoms)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Broadcasting

(defn- encode-data
  [data]
  ;; TODO encode this with something other than str
  (-> data pr-str))

(defn- retract-connection!
  [conn conn-id]
  (d/transact conn {:tx-data [[:db/retractEntity [:aws.apigw.ws.connection/id conn-id]]]}))

(defn send-data!
  [{:keys [remote] :as config} conn-id msg]
  (let [uri       (URI. (:ws-uri remote))
        msg-bytes (-> (encode-data msg) .getBytes SdkBytes/fromByteArray)
        client    (.. (ApiGatewayManagementApiClient/builder)
                      (endpointOverride uri)
                      (build))
        request   (.. (PostToConnectionRequest/builder)
                      (connectionId conn-id)
                      (data msg-bytes)
                      (build))]
    (try
      (.postToConnection client request)
      (catch Exception e
        (retract-connection! (get-conn config) conn-id)))))

(defn broadcast-datoms!
  [config db datoms]
  (let [plan  (multiplex-datoms db datoms)
        table (make-lookup-table db datoms)]
    (doseq [[cid datoms] plan]
      (send-data! config cid {:datoms       datoms
                              :eid->lookups table}))))

(defn broadcast-tx-result!
  [config tx-data]
  (broadcast-datoms! config (:db-after tx-data) (:tx-data tx-data)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Connection management

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
    {:status 200
     :body   "disconnected"}))

(defn on-message
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketMessageEvent" ::input input})
    {:status 200
     :body   (encode-data {:conn-id conn-id})}))
