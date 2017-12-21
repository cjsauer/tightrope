(ns {{namespace}}.http-server
  (:require [mount.core :refer [defstate]]
            [org.httpkit.server :refer [run-server]]
            [{{namespace}}.rest-routes :refer [app-handler]]
            [{{namespace}}.config :refer [config]]))

(defn- start-server
  [{:as config :keys [http-port]}]
  (run-server (app-handler) {:port http-port}))

(defstate http-server
  :start (start-server (config))
  :stop (when-not (nil? http-server)
          (http-server :timeout 100)))
