(ns tightrope.server.ions
  (:require [cheshire.core :as json]
            [datomic.client.api :as d]
            [datomic.ion.cast :as icast]
            [datomic.ion.lambda.api-gateway :as apigw]
            [tightrope.server.handler :as handler]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.connect.datomic :as pcd]
            [com.wsscode.pathom.connect.datomic.client :refer [client-config]]
            ))

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

(defn on-connect*
  [{:keys [input event]}]
  (let [payload (json/parse-string input keyword)]
    (icast/event {:msg "TightropeWebSocketConnectEvent" ::input input ::event event})
    {:status 200
     :body   "connected"}))
(def on-connect (apigw/ionize on-connect*))

(defn on-disconnect*
  [{:keys [input event]}]
  (let [payload (json/parse-string input keyword)]
    (icast/event {:msg "TightropeWebSocketDisconnectEvent" ::input input ::event event})
    {:status 200
     :body   "disconnected"}))
(def on-disconnect (apigw/ionize on-disconnect*))

(defn on-message*
  [{:keys [input event]}]
  (let [payload (json/parse-string input keyword)]
    (icast/event {:msg "TightropeWebSocketMessageEvent" ::input input ::event event})
    {:status 200
     :body   "message receieved"}))
(def on-message (apigw/ionize on-message*))
