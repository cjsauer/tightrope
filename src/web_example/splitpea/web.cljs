(ns splitpea.web
  "Entry point of the splitpea web application"
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [rum.core :as rum]
            [datascript.core :as ds]
            [tightrope.client :as rope]
            [splitpea.model :as model]
            [splitpea.root :as root]
            [splitpea.resolvers :as shared-resolvers]
            [splitpea.client.resolvers :as client-resolvers]))

(defn- authz-middleware
  [{:keys [parser]} req]
  (if-let [token (-> (parser {} [:user/token]) :user/token)]
    (update req :headers merge {"Authorization" token})
    req))

(defonce app-ctx (rope/make-framework-context
                  {:schema    model/schema
                   :resolvers (concat shared-resolvers/all
                                      client-resolvers/all)
                   :remote    {:path "/api"
                               :request-middleware authz-middleware}}))

(defn ^:dev/after-load mount
  []
  (rum/mount
   (rope/ctx-provider app-ctx (root/root))
   (.getElementById js/document "app")))

(defn start!
  []
  (mount))
