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
            PostToConnectionRequest]
           [software.amazon.awssdk.core SdkBytes]
           ))

(def connection-schema
  [{:db/ident       ::connection-id
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/noHistory   true
    :db/doc         "API Gateway WS connection ID"}

   {:db/ident       ::watch
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/noHistory   true
    :db/doc         "Signals an API Gateway WS connection's intent to watch this entity"}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket handling

;; TODO: wrap this in mutations/resolvers so it can be easily plugged in to the rest of TR

;; subscribe!
;; - given a lookup only (no query)
;; - backend stores [lookup conn]
;;   - `::connections` attribute
;;     - :db/noHistory and :db/index (to take advantage of AVET index)
;;     - card-many str-type attribute
;;     - transacted onto entities to signify a connection's listening intent
;;     - use the ::_connections attribute in order to build conn->eids map
;; - backend sends all datoms matching lookup to subscribed connections
;; - takes optional datoms to be transacted onto the connection entity (reified subscribtions)
;;   - e.g. user ID
;;   - allows you to do things like track who has watched (seen) entities and when
;;   - the connection attributes are noHistory by default

;; can we assume ::connections changes will never exist in the same tx as "data changes"?
;;   - probably a safe assumption. controlled by apigw connect/disconnect events (and send exceptions)

(defn subscribe!
  [conn connId lookups]
  (d/transact conn {:tx-data [{::connection-id connId
                               ::watch         lookups}]}))

;;
;; unsubscribe!
;; - dissoc [conn lookup] from backend store

;; "transact" is an important (built-in) mutation
;; - if the problem of trust can be solved, then the wire disappears
;;   - ::authz/symbol is a card-one symbol attribute
;;     - predicate (fn [db conn-id datom])
;;     - resolved at broadcast time per datom (from tx-data)

(defn unsubscribe-tx
  [db connId lookups]
  (mapv #(vector :db/retract (first %) ::watch (second %))
        (d/q '[:find ?conn ?watched
               :in $ ?cid [[?ident-attr ?ident] ...]
               :where
               [?conn ::connection-id ?cid]
               [?conn ::watch ?watched]
               [?watched ?ident-attr ?ident]]
             db connId lookups)))

(defn unsubscribe!
  [conn connId lookups]
  (let [retractions (unsubscribe-tx (d/db conn) connId lookups)]
    (d/transact conn {:tx-data retractions})))




(defn multiplex-datoms
  [db tx-data]
  (->> (d/q '[:find ?cid ?e ?attr ?v ?tx ?op
              :in $ [[?e ?a ?v ?tx ?op] ...]
              :where
              [?conn ::watch ?e]
              [?conn ::connection-id ?cid]
              [?a :db/ident ?attr]]
            db
            tx-data)
       (reduce (fn [plan cid-datom]
                 (update plan (first cid-datom) (fnil conj #{}) (subvec cid-datom 1)))
               {})))

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

(defn send-data!
  [conn-id msg]
  (let [uri       (URI. (str "https://7ps9rxk22d.execute-api.us-east-1.amazonaws.com" "/" "dev"))
        msg-bytes (-> msg str bytes) ;; TODO encode this with something other than str
        client    (.. (ApiGatewayManagementApiClient/builder)
                      (endpointOverride uri)
                      (build))
        request   (.. (PostToConnectionRequest/builder)
                      (connectionId conn-id)
                      (data msg-bytes)
                      (build))]
    (.postToConnection client request)))

;; plan looks like:
;;
;; {"c1" [["c1" 15256823347019860 :user/age 28 13194139533332 true]
;;        ["c1" 39424088925536341 :user/age 26 13194139533332 true]]
;;  "c2" [...]}

(defn broadcast-datoms!
  [db datoms]
  (let [plan  (multiplex-datoms db datoms)
        table (make-lookup-table db datoms)]
    (doseq [[cid datoms] plan]
      (send-data! cid {:datoms       datoms
                       :eid->lookups table}))))

(comment

  (subscribe-tx nil "c1" [:ident 1] [:ident 2])

  (let [;; simulate the ::connections avet index
        connections {"c1" #{1 2}
                     "c2" #{2}
                     "c3" #{1 3}}
        datoms      [[1 :x "x" 111 true]
                     [2 :y "y" 111 true]
                     [3 :z "z" 111 false]]]
    ;; goal: send 1 payload to each connection
    (reduce (fn multiplex-datoms [conn->payload [c eids]]
              (assoc conn->payload c
                     (filter #(-> % first eids) datoms)))
            {}
            connections))

  )

(defn on-connect*
  [{::edngw/keys [data]}]
  (let [{:keys [connectionId]} (:requestContext data)]
    (icast/event {:msg "TightropeWebSocketConnectEvent" ::data data})
    {:status 200
     :body   "connected"}))

(def on-connect (apigw/ionize on-connect*))

(defn on-disconnect*
  [{::edngw/keys [data]}]
  (let [{:keys [connectionId]} (:requestContext data)]
    (icast/event {:msg "TightropeWebSocketDisconnectEvent" ::data data})
    {:status 200
     :body   "disconnected"}))
(def on-disconnect (apigw/ionize on-disconnect*))

(defn on-message*
  [input]
  (let [{:keys [body] :as data} (::edngw/data input)
        {:keys [connectionId]}  (:requestContext data)]
    (icast/event {:msg "TightropeWebSocketMessageEvent" ::input (str input) ::data data
                  ::body body ::conn-id connectionId})
    (send-data! connectionId body )
    {:status 200
     :body   "message receieved"}))
(def on-message (apigw/ionize on-message*))

(comment


  )
