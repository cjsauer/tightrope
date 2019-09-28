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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; query

(defn- handle-query-success
  [ctx lookup response]
  (let [{:keys [body]} response
        entity         (get body lookup)
        e-with-lookup  (conj entity lookup)]
    e-with-lookup))

(defn q
  ([ctx]
   (q ctx (:lookup ctx) (:query ctx)))
  ;;
  ([ctx target]
   (q ctx (:lookup target) (:query target)))
  ;;
  ([ctx lookup query]
   (go
     (let [full-query       [{lookup query}]
           req              {:transit-params full-query}
           {:keys [status]
            :as   response} (<! (post! ctx req))]
       (cond
         (< status 300) (handle-query-success ctx lookup response)
         :default       (throw (ex-info "Query responded with non-200 status"
                                        {:response response})))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; freshen

;; TODO: rapid calls to freshen! should be batched into a single query

(defn freshen!
  ([ctx]
   (freshen! ctx (:lookup ctx) (:query ctx)))
  ;;
  ([ctx target]
   (freshen! ctx (:lookup target) (:query target)))
  ;;
  ([{:keys [conn] :as ctx} lookup query]
   (ds/transact! conn [(conj {:ui/freshening? true} lookup)])
   (go
     (let [entity             (<! (q ctx lookup query))
           freshen-retraction [:db.fn/retractAttribute lookup :ui/freshening?]]
       (ds/transact! conn [entity
                           freshen-retraction])
       entity))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; mutate

;; TODO: :ui/mutation? should be a set of currently in flight mutations,
;; i.e. :ui/in-flight-mutations

(defn- handle-mutation-success
  [{:keys [conn]} lookup m response]
  (let [{:keys [body]}      response
        entity              (get body m)
        entity-with-lookup  (conj entity lookup)
        mutating-retraction [:db.fn/retractAttribute lookup :ui/mutating?]]
    (ds/transact! conn [entity-with-lookup
                        mutating-retraction])
    entity-with-lookup))

(defn mutate!
  ([ctx mut args]
   (mutate! ctx (:lookup ctx) (:query ctx) mut args))
  ;;
  ([ctx target mut args]
   (mutate! ctx (:lookup target) (:query target) mut args))
  ;;
  ([{:keys [conn] :as ctx} lookup query mut args]
   (ds/transact! conn [(conj {:ui/mutating? true} lookup)])
   (go
     (let [full-mutation    [{`(~mut ~args) query}]
           req              {:transit-params full-mutation}
           {:keys [status]
            :as   response} (<! (post! ctx req))]
       (cond
         (< status 300) (handle-mutation-success ctx lookup mut response)
         :default       (throw (ex-info "Mutation responded with non-200 status"
                                        {:response response})))))))
