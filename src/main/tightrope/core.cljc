(ns tightrope.core
  "Mount datascript entities to the UI"
  (:require ["react" :as react]
            [rum.core :as rum]
            [datascript.core :as ds]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.sugar]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities

(defn try-pull
  [db selector eid]
  (try
    (ds/pull db selector eid)
    #?(:clj  (catch Exception e nil)
       :cljs (catch :default e nil))))

(defn upsert
  [lookup m]
  (let [lookup-map (if (vector? lookup)
                     (apply hash-map lookup)
                     {:db/id lookup})]
    (merge lookup-map m)))

(defn upsert!
  [conn lookup m]
  (ds/transact! conn [(upsert lookup m)]))

(defn entity->lookup
  [e & ks]
  (loop [k (first ks)]
    (when k
      (if (contains? e k)
        [k (get e k)]
        (recur (next ks))))))

(defn eids->lookups
  [db & eids]
  (ds/q '[:find ?attr ?value
          :in $ [[?attr [[?aprop ?avalue] ...]] ...] [?eids ...]
          :where
          [(= ?avalue :db.unique/identity)]
          [?eids ?attr ?value]]
        db (:schema db) eids))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Framework code

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

(defn- get-ctx
  [{:rum/keys [react-component]}]
  (js->clj
   (.-context react-component)
   :keywordize-keys true))

(defn- get-props
  [state]
  (-> state :rum/args first))

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

(defn- assoc-args
  [state props]
  (assoc state :rum/args [props]))

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

(def ^:private TightropeContext (react/createContext))

(defn ds-mixin
  [& [{:keys [mount-tx unmount-tx]
       :as   opts}]]
  {:static-properties {:contextType TightropeContext}
   ;; -------------------------------------------------------------------------------
   :will-mount        (fn did-mount [{:rum/keys [react-component] :as state}]
                        (let [{:keys [conn
                                      registry
                                      lookup]} (parse-state state opts)
                              rerender-fn      #(rum/request-render react-component)]
                          (when mount-tx
                            (ds/transact! conn mount-tx))
                          (if lookup
                            (do (swap! registry add-fn-to-registry lookup rerender-fn)
                                (assoc state :rerender-fn rerender-fn))
                            state)))
   ;; -------------------------------------------------------------------------------
   :did-unmount       (fn did-unmount [state]
                        (let [{:keys [conn
                                      registry
                                      lookup]} (parse-state state opts )
                              fn-to-remove     (:rerender-fn state)]
                          (when unmount-tx
                            (ds/transact! conn unmount-tx))
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
                                      props]} (parse-state state opts)
                              db              (ds/db conn)
                              pull-result     (try-pull db query lookup)
                              full-query      [{lookup query}]
                              parse-env       (cond-> {:conn conn}
                                                pull-result (assoc ::p/entity {lookup pull-result}))
                              parse-result    (parser parse-env full-query)
                              data            (->> (get parse-result lookup)
                                                   (inject-known-lookups-recursively db))
                              new-props       (cond-> props
                                                data (assoc ::data data)
                                                conn (assoc ::conn conn))]
                          (assoc-args state new-props)))
   })

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
  [{:keys [resolvers]}]
  (p/parser
   {::p/env     {::p/reader               [p/map-reader
                                           pc/reader2
                                           pc/open-ident-reader
                                           p/env-placeholder-reader]
                 ::p/placeholder-prefixes #{">"}}
    ::p/mutate  pc/mutate-async
    ::p/plugins [(pc/connect-plugin {::pc/register (or resolvers [])})
                 p/error-handler-plugin
                 p/elide-special-outputs-plugin
                 p/trace-plugin]}))

(defn make-framework-context
  [{:keys [schema parser resolvers]}]
  (let [conn     (ds/create-conn schema)
        registry (atom {})]
    (ds/listen! conn ::listener (partial on-tx registry))
    {:conn     conn
     :registry registry
     :parser   (or parser
                   (default-parser {:resolvers resolvers}))
     }))

(defn ctx-provider
  [ctx & children]
  (let [provider      (.-Provider TightropeContext)
        props         (clj->js {:value ctx})]
    (apply react/createElement provider props children)))

(defn reset-registry!
  [ctx]
  (reset! (:registry ctx) {}))
