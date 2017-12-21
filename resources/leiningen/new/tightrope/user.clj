(ns user
  (:require [clojure.pprint :refer [pp pprint]]
            [clojure.tools.namespace.repl :refer [refresh refresh-all]]
            [com.stuartsierra.component :as component]
            [mount.core :as mount :refer [defstate]]
            [figwheel-sidecar.config :as fw-cfg]
            [figwheel-sidecar.system :as fw-sys]
            [garden-watcher.core :refer [new-garden-watcher]]
            [{{namespace}}.http-server]))

(defstate figwheel
  :start (-> (fw-sys/figwheel-system (fw-cfg/fetch-config))
             component/start)
  :stop (component/stop figwheel))

(defstate css-watcher
  :start (-> (fw-sys/css-watcher {:watch-paths ["resources/public/css"]
                                  :figwheel-server figwheel})
             (component/start))
  :stop (component/stop css-watcher))


(defstate garden-watcher
  :start (-> (new-garden-watcher '[cjsauer.example-app.styles])
             component/start)
  :stop (component/stop garden-watcher))

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

(defn cljs-repl
  []
  (fw-sys/cljs-repl figwheel))
