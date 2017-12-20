(ns user
  (:require [clojure.pprint :refer [pp pprint]]
            [{{namespace}}.http-server]
            [mount.core :as mount]))

(defn start
  []
  (mount/start))

(defn stop
  []
  (mount/stop))
