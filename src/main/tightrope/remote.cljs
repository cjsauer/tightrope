(ns tightrope.remote
  (:require [cljs-http.client :as http]
            [cljs.core.async :as a :refer [go <!]]
            [datascript.core :as ds]))

(defn- handle-freshen-success
  [{:keys [conn]} lookup response]
  (let [{:keys [body]}     response
        entity             (get body lookup)
        entity-with-lookup (conj entity lookup)
        freshen-retraction [:db.fn/retractAttribute lookup :ui/freshening?]]
    (ds/transact! conn [entity-with-lookup
                        freshen-retraction])))

;; TODO: rapid calls to freshen! should be batched into a single query
(defn freshen!
  [{:keys [remote conn] :as ctx} lookup query]
  (ds/transact! conn [(conj {:ui/freshening? true} lookup)])
  (go
    (let [full-query [{lookup query}]
          {:keys [status] :as response}
          (<! (http/post (:path remote) {:edn-params full-query}))]
      (cond
        (< status 300) (handle-freshen-success ctx lookup response)
        :default (throw (ex-info "Freshen responded with non-200 status"
                                 {:response response}))))))

;; TODO: :ui/mutation? should be a set of currently in flight mutations,
;; i.e. :ui/in-flight-mutations

(defn- handle-mutation-success
  [{:keys [conn lookup]} mutation response]
  (let [{:keys [body]}      response
        entity              (get body mutation)
        entity-with-lookup  (conj entity lookup)
        mutating-retraction [:db.fn/retractAttribute lookup :ui/mutating?]]
    (ds/transact! conn [entity-with-lookup
                        mutating-retraction])))

(defn mutate!
  [{:keys [conn remote lookup query] :as ctx} mutation args]
  (ds/transact! conn [(conj {:ui/mutating? true} lookup)])
  (go
    (let [full-mutation [{`(~mutation ~args) query}]
          {:keys [status] :as response}
          (<! (http/post (:path remote) {:edn-params full-mutation}))]
      (cond
        (< status 300) (handle-mutation-success ctx mutation response)
        :default (throw (ex-info "Mutation responded with non-200 status"
                                 {:response response}))))))
