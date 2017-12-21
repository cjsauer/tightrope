(ns {{namespace}}.core
    (:gen-class)
    (:require [mount.core :as mount]
              [{{namespace}}.http-server]))

(defn -main
  [& args]
  (mount/start))
