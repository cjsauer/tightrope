(ns tightrope.remote
  (:require [cljs-http.client :as http]
            [cljs.core.async :as a :refer [go <!]]
            [datascript.core :as ds]))

(defn- handle-success
  [{:keys [conn]} lookup query response]
  (let [{:keys [body]}     response
        entity             (get body lookup)
        entity-with-lookup (conj entity lookup)
        freshen-retraction [:db.fn/retractAttribute lookup :ui/freshening?]]
    (ds/transact! conn [entity-with-lookup
                        freshen-retraction])))

(defn freshen!
  [{:keys [remote conn] :as ctx} lookup query]
  (go
    (when remote
      (ds/transact! conn [(conj {:ui/freshening? true} lookup)])
      (let [full-query [{lookup query}]
            {:keys [status] :as response}
            (<! (http/post (:path remote) {:edn-params full-query}))]
        (cond
          (< status 300) (handle-success ctx lookup query response)
          :default (throw (ex-info "Freshen responded with non-200 status"
                                   {:response response})))))))
