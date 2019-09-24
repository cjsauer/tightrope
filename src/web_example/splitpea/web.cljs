(ns splitpea.web
  "Entry point of the splitpea web application"
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [rum.core :as rum]
            [datascript.core :as ds]
            [tightrope.core :as rope]
            [splitpea.model :as model]
            [splitpea.root :as root]))

(defonce app-ctx (rope/make-framework-context
                  {:schema model/schema}))

(defn ^:dev/after-load mount
  []
  (rope/reset-registry! app-ctx)
  (rum/mount
   (rope/ctx-provider app-ctx (root/root {:lookup [:db/ident ::root/root]}))
   (.getElementById js/document "app")))

(defn start!
  []
  (ds/transact! (:conn app-ctx) [(:init-tx root/*root)])
  (mount))
