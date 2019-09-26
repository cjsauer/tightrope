(ns tightrope.remote
  (:require [cljs-http.client :as http]
            [cljs.core.async :as a :refer [go <!]]
            [datascript.core :as ds]))

(defn- post!
  [{:keys [remote] :as ctx} req]
  (go
    (let [req-middleware-fn  (get remote :request-middleware (fn [_ r] r))
          resp-middleware-fn (get remote :response-middleware (fn [_ r] r))
          mw-req             (req-middleware-fn ctx req)
          full-req           (update mw-req :headers merge {"Accept" "application/transit+json"})
          resp               (<! (http/post (:uri remote) full-req))]
      (resp-middleware-fn ctx resp))))

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
  [{:keys [conn] :as ctx} lookup query]
  (ds/transact! conn [(conj {:ui/freshening? true} lookup)])
  (go
    (let [full-query       [{lookup query}]
          req              {:transit-params full-query}
          {:keys [status]
           :as   response} (<! (post! ctx req))]
      (cond
        (< status 300) (handle-freshen-success ctx lookup response)
        :default       (throw (ex-info "Freshen responded with non-200 status"
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
  [{:keys [conn lookup query] :as ctx} mutation args]
  (ds/transact! conn [(conj {:ui/mutating? true} lookup)])
  (go
    (let [full-mutation    [{`(~mutation ~args) query}]
          req              {:transit-params full-mutation}
          {:keys [status]
           :as   response} (<! (post! ctx req))]
      (cond
        (< status 300) (handle-mutation-success ctx mutation response)
        :default       (throw (ex-info "Mutation responded with non-200 status"
                                       {:response response}))))))
