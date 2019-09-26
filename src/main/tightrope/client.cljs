(ns tightrope.client
  "Mount datascript entities to the UI"
  (:require ["react" :as react]
            [rum.core :as rum]
            [datascript.core :as ds]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.sugar]
            [tightrope.remote :as remote]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities

(defn try-pull
  [db selector eid]
  (try
    (ds/pull db selector eid)
    (catch :default e nil)))

(defn upsertion
  [lookup m]
  (let [lookup-map (if (vector? lookup)
                     (apply hash-map lookup)
                     {:db/id lookup})]
    (merge lookup-map m)))

(defn upsert!
  [conn lookup m]
  (ds/transact! conn [(upsertion lookup m)]))

(defn mutate!
  [ctx mutation args]
  ;; Remote mutation only
  (remote/mutate! ctx mutation args))

(defn mutate-optimistic!
  [{:keys [parser conn lookup query] :as ctx} mutation args]
  ;; Remote mutation
  (mutate! ctx mutation args)
  ;; Optimistic (local) mutation
  (let [full-mutation [{`(~mutation ~args) query}]
        local-result (parser {} full-mutation)
        mutation-result (get local-result mutation)]
    (ds/transact! conn [(upsertion lookup mutation-result)])))

(defn entity->lookup
  [e & ks]
  (loop [k (first ks)]
    (when k
      (if (contains? e k)
        [k (get e k)]
        (recur (first (next ks)))))))

(defn eids->lookups
  [db & eids]
  (into (ds/q '[:find ?attr ?value
                :in $ [[?attr [[?aprop ?avalue] ...]] ...] [?eids ...]
                :where
                [(= ?avalue :db.unique/identity)]
                [?eids ?attr ?value]]
              db (:schema db) eids)
        (map #(vector :db/id %) eids)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Registry
;;   - Map of lookup -> function
;;   - Functions are presumably re-render functions to reactively
;;     update UI following datascript transactions

(defn- add-fn-to-registry
  [registry lookup f]
  (update registry lookup (fnil conj #{}) f))

(defn- remove-fn-from-registry
  [registry lookup fn-to-remove]
  (let [rerender-fns     (get registry lookup)
        new-rerender-fns (disj rerender-fns fn-to-remove)]
    (if (empty? new-rerender-fns)
      (dissoc registry lookup)
      (assoc registry lookup new-rerender-fns))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rum-specifics / React Lifecycle

(defn- get-ctx
  [{:rum/keys [react-component]}]
  (js->clj
   (.-context react-component)
   :keywordize-keys true))

(defn- get-props
  [state]
  (or (-> state :rum/args first) {}))

(defn- props-or-opts
  [props opts k]
  (or (get props k)
      (get opts k)))

(defn- derive-lookup
  [props opts]
  (or (props-or-opts props opts :lookup)
      (let [idents (:idents opts)]
        (apply entity->lookup props idents))))

(defn- parse-state
  [state & [opts]]
  (let [ctx            (get-ctx state)
        props          (get-props state)
        derived-lookup {:lookup (derive-lookup props opts)}]
    (merge ctx opts derived-lookup {:props props})))

(defn- inject-known-lookups
  [db e]
  (if-let [eid (:db/id e)]
    (let [lookups (eids->lookups db eid)]
      (into e lookups))
    e))

(defn- inject-known-lookups-recursively
  [db e]
  (let [f (fn [new-e [k v]]
            (if (map? v)
              (->> (inject-known-lookups db v)
                   (inject-known-lookups-recursively db)
                   (assoc new-e k))
              (assoc new-e k v)))]
    (reduce f {} e)))

(defn- component-query
  [{:keys [conn parser lookup query]}]
  (when (and lookup query)
    (let [db              (ds/db conn)
          pull-result     (try-pull db query lookup)
          full-query      [{lookup query}]
          parse-env       (cond-> {:conn conn}
                            pull-result (assoc ::p/entity {lookup pull-result}))
          parse-result    (parser parse-env full-query)
          data            (get parse-result lookup)]
      (inject-known-lookups-recursively db data))))

(def ^:private TightropeContext (react/createContext))

(defn ds-mixin
  [& [{:as   opts
       :keys [mount-tx
              unmount-tx
              freshen?
              auto-retract?
              ]}]]
  {:static-properties {:contextType TightropeContext}
   ;; -------------------------------------------------------------------------------
   :will-mount        (fn will-mount [{:rum/keys [react-component] :as state}]
                        (let [{:keys [conn
                                      registry
                                      lookup
                                      query] :as s} (parse-state state opts)
                              rerender-fn #(rum/request-render react-component)]
                          (when mount-tx
                            (ds/transact! conn mount-tx))
                          (when (and freshen? lookup query)
                            (remote/freshen! s lookup query))
                          (if lookup
                            (do (swap! registry add-fn-to-registry lookup rerender-fn)
                                (assoc state :rerender-fn rerender-fn))
                            state)))
   ;; -------------------------------------------------------------------------------
   :will-unmount       (fn will-unmount [state]
                         (let [{:keys [conn
                                       registry
                                       lookup]} (parse-state state opts )
                               fn-to-remove     (:rerender-fn state)]
                           (when unmount-tx
                             (ds/transact! conn unmount-tx))
                           (when (and auto-retract? lookup)
                             (ds/transact! conn [[:db/retractEntity lookup]]))
                           (if (and lookup fn-to-remove)
                             (do
                               (swap! registry remove-fn-from-registry lookup fn-to-remove)
                               (dissoc state :rerender-fn))
                             state)))
   ;; -------------------------------------------------------------------------------
   :before-render     (fn before-render [state]
                        (let [{:keys [conn
                                      parser
                                      lookup
                                      query
                                      props] :as s} (parse-state state opts)
                              data       (component-query s)
                              upsert!    (partial upsert! conn lookup)
                              mutate!    (partial mutate! s)
                              freshen!   (partial remote/freshen! s lookup query)
                              new-props  (cond-> props
                                           data   (merge data)
                                           lookup (assoc ::upsert! upsert!)
                                           lookup (assoc ::mutate! mutate!)
                                           lookup (assoc ::freshen! freshen!)
                                           )]
                          (assoc state :rum/args [new-props])))
   })

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Framework context/state

(defn on-tx
  [registry {:keys [db-after tx-data]}]
  (let [affected-eids    (map :e tx-data)
        affected-lookups (concat (apply eids->lookups db-after affected-eids)
                                 affected-eids)
        rerender-fns     (mapcat #(get @registry %)
                                 (set affected-lookups))]
    (doseq [rrf rerender-fns]
      (rrf))))

(defn- default-parser
  [{:keys [resolvers] :as ctx}]
  (let [additional-env (dissoc ctx :reslovers)]
    (p/parser
     {::p/env     (merge
                   {::p/reader               [p/map-reader
                                              pc/reader2
                                              pc/open-ident-reader
                                              p/env-placeholder-reader]
                    ::p/placeholder-prefixes #{">"}}
                   additional-env)
      ::p/mutate  pc/mutate
      ::p/plugins [(pc/connect-plugin {::pc/register (or resolvers [])})
                   p/error-handler-plugin
                   p/elide-special-outputs-plugin
                   p/trace-plugin]})))

(defn ropeid
  []
  (str (random-uuid)))

(defn- enrich-schema
  [schema]
  (assoc schema ::id {:db/unique :db.unique/identity}))

(defn make-framework-context
  [{:keys [schema parser remote] :as ctx}]
  (let [conn     (-> schema enrich-schema ds/create-conn)
        registry (atom {})]
    (ds/listen! conn ::listener (partial on-tx registry))
    {:conn     conn
     :registry registry
     :parser   (or parser
                   (default-parser ctx))
     :remote   remote
     }))

(defn ctx-provider
  [ctx & children]
  (let [provider      (.-Provider TightropeContext)
        props         (clj->js {:value ctx})]
    (apply react/createElement provider props children)))
