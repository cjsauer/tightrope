(ns tightrope.server.ions
  (:require [cheshire.core :as json]
            [datomic.client.api :as d]
            [datomic.ion.cast :as icast]
            [datomic.ion.lambda.api-gateway :as apigw]
            [datomic.ion.edn.api-gateway :as edngw]
            [tightrope.server.handler :as handler]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.connect.datomic :as pcd]
            [com.wsscode.pathom.connect.datomic.client :refer [client-config]]
            )
  (:import [java.net URI]
           [software.amazon.awssdk.services.apigatewaymanagementapi
            ApiGatewayManagementApiClient
            ApiGatewayManagementApiAsyncClient
            ApiGatewayManagementApiAsyncClientBuilder]
           [software.amazon.awssdk.services.apigatewaymanagementapi.model
            GetConnectionRequest
            PostToConnectionRequest]
           [software.amazon.awssdk.core SdkBytes]))

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
    (let [conn (d/connect client {:db-name db-name})
          db   (d/db conn)]
      (load-schemas conn schemas)
      conn)))

(defn get-conn
  [config]
  (ensure-schemas config))

(defn get-db
  [config]
  (d/db (get-conn config)))

(defn ion-handler
  [config]
  (let [conn           (get-conn config)
        env            {:conn conn}
        plugins        [(pcd/datomic-connect-plugin (assoc client-config ::pcd/conn conn))]
        handler-config (select-keys config [:path :parser :parser-opts])
        merged-config  (-> handler-config
                           (update-in [:parser-opts :env] merge env)
                           (update-in [:parser-opts :plugins] (fnil concat []) plugins))]
    (handler/tightrope-handler merged-config)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket handling

;; (def apigwm (aws/client {:api    :apigatewaymanagementapi
;;                          :region "us-east-1"
;;                          :endpoint-override (str "7ps9rxk22d.execute-api.us-east-1.amazonaws.com"
;;                                                  "/"
;;                                                  "dev")}))

(defn send-message!
  [msg connIds]
  (doseq [id connIds]
    ;; TODO: try/catch to remove bad connections
    #_(aws/invoke apigwm {:op      :PostToConnection
                        :request {:ConnectionId id
                                  :Data         (str msg)
                                  }})))

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
    (send-message! body [connectionId])
    {:status 200
     :body   "message receieved"}))
(def on-message (apigw/ionize on-message*))

(comment

  (let [uri    (URI. (str "https://7ps9rxk22d.execute-api.us-east-1.amazonaws.com" "/" "dev"))
        client (.. (ApiGatewayManagementApiClient/builder)
                   (endpointOverride uri)
                   (build))
        request (.. (PostToConnectionRequest/builder)
                    (connectionId "B3vOJdSdoAMCKIA=")
                    (data (bytes "hello"))
                    (build))]
    (.postToConnection client request))

  )
