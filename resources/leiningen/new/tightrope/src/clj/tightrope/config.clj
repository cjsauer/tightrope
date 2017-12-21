(ns {{namespace}}.config
  (:require [environ.core :refer [env]]
            [mount.core :refer [defstate]]))

(defstate config
  :start {:port (or (env :port) 8080)})
