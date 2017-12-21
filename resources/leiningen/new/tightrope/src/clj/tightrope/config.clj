(ns {{namespace}}.config
  (:require [environ.core :refer [env]]))

(defn config
  []
  {:http-port (or (env :http-port) 8080)})
