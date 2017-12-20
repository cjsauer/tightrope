(ns {{namespace}}.http-server
  (:require [mount.core :refer [defstate]]
            [org.httpkit.server :refer [run-server]]
            [{{namespace}}.rest-routes :refer [app-routes]]))

(defstate http-server
  :start (run-server #'app-routes {:port 8080})
  :stop (when-not (nil? http-server)
          (http-server :timeout 100)))
