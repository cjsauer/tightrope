(ns user
  (:require [clojure.pprint :refer [pp pprint]]
            [clojure.tools.namespace.repl :refer [refresh refresh-all]]
            [{{namespace}}.http-server]
            [mount.core :as mount]))

(defn start
  []
  (mount/start))

(defn stop
  []
  (mount/stop))

(defn reset
  []
  (stop)
  (refresh)
  (start))
