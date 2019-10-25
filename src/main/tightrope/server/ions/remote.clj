(ns tightrope.server.ions.remote
  (:require [datomic.client.api :as d]
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


(def connection-schema
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity subscriptions

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
;; Multiplexing

(defn multiplex-datoms
  [db tx-data]
  (->> (d/q '[:find ?cid ?e ?attr ?v ?tx ?op
              :in $ [[?e ?a ?v ?tx ?op] ...]
              :where
              [?conn ::watch ?e]
              [?conn :aws.apigw.ws.connection/id ?cid]
              [?a :db/ident ?attr]]
            db
            tx-data)
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
  (reduce (fn [table [e & _]]
            (assoc table e (eid->lookups db e)))
          {}
          datoms))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Broadcasting

(defn- encode-data
  [data]
  ;; TODO encode this with something other than str
  (-> data pr-str .getBytes SdkBytes/fromByteArray))

(defn send-data!
  [{:keys [remote]} conn-id msg]
  (let [uri       (URI. (:ws-uri remote))
        msg-bytes (encode-data msg)
        client    (.. (ApiGatewayManagementApiClient/builder)
                      (endpointOverride uri)
                      (build))
        request   (.. (PostToConnectionRequest/builder)
                      (connectionId conn-id)
                      (data msg-bytes)
                      (build))]
    (.postToConnection client request)))

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

(defn xact!
  [conn tx-data]
  (-> (d/transact conn {:tx-data tx-data})
      broadcast-tx-result!))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Connection management

(defn on-connect
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketConnectEvent" ::input input})
    {:status 200
     :body   (encode-data {:conn-id conn-id})}))

(defn on-disconnect
  [config input]
  (let [conn-id (-> input ::edngw/data :requestContext :connectionId)]
    (icast/event {:msg "TightropeWebSocketDisconnectEvent" ::input input})
    {:status 200
     :body   (encode-data {:conn-id conn-id})}))

(defn on-message
  [config input]
  (let [body    (-> input ::edngw/data :body)
        conn-id (-> input ::edngw/data :requestContext :connectionI)]
    (icast/event {:msg "TightropeWebSocketMessageEvent" ::input input})
    {:status 200
     :body   body}))
