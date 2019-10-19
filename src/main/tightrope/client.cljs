(ns tightrope.client
  "Mount datascript entities to the UI"
  (:require ["react" :as react]
            [rum.core :as rum]
            [datascript.core :as ds]
            [cjsauer.pathom.connect.datascript :as pcd]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [tightrope.remote :as remote]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities

(defn entity->lookup
  [e & idents]
  (loop [k (first idents)]
    (when k
      (if-let [v (get e k)]
        [k (if (coll? v)
             (first v)
             v)]
        (recur (first (next idents)))))))

(defn eids->lookups
  [db & eids]
  (ds/q '[:find ?attr ?value
          :in $ [[?attr [[?aprop ?avalue] ...]] ...] [?eids ...]
          :where
          [(= ?avalue :db.unique/identity)]
          [?eids ?attr ?value]]
        db (:schema db) eids))

(defn try-pull
  [db selector eid]
  (try
    (ds/pull db selector eid)
    (catch :default e nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; upsert

(defn upsertion
  [lookup m]
  (let [lookup-map (if (vector? lookup)
                     (apply hash-map lookup)
                     {:db/id lookup})]
    (merge lookup-map m)))

(defn upsert!
  [conn lookup m]
  (ds/transact! conn [(upsertion lookup m)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; query

(defn q
  ([ctx]
   (q ctx (:lookup ctx) (:query ctx)))
  ;; ---------------------------------------------
  ([ctx target]
   (q ctx (:lookup target) (:query target)))
  ;; ---------------------------------------------
  ([{:keys [conn parser] :as ctx} lookup query]
   (let [full-query   [{lookup query}]
         parse-result (parser {} full-query)]
     (get parse-result lookup))))

;; Re-export of remote query
(def q+ remote/q)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; freshen

(defn freshen!
  ([ctx]
   (let [res (q ctx)]
     (upsert! (:conn ctx) (:lookup ctx) res)
     res))
  ;; ---------------------------------------------
  ([ctx target]
   (let [res (q ctx target)]
     (upsert! (:conn ctx) (:lookup target) res)
     res))
  ;; ---------------------------------------------
  ([ctx lookup query]
   (let [res (q ctx lookup query)]
     (upsert! (:conn ctx) lookup res)
     res)))

;; Re-export of remote freshen
(def freshen!! remote/freshen!)

(defn freshen!!!
  ([ctx]
   (freshen! ctx)
   (remote/freshen! ctx))
  ;; ---------------------------------------------
  ([ctx target]
   (freshen! ctx target)
   (remote/freshen! ctx target))
  ;; ---------------------------------------------
  ([ctx lookup query]
   (freshen! ctx lookup query)
   (remote/freshen! ctx lookup query)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; mutate

(defn mutate!
  ([ctx m args]
   (mutate! ctx (:lookup ctx) (:query ctx) m args))
  ;; ---------------------------------------------
  ([ctx target m args]
   (mutate! ctx (:lookup target) (:query target) m args))
  ;; ---------------------------------------------
  ([{:keys [parser conn]} lookup query m args]
   (let [full-mutation [{`(~m ~args) query}]
         result        (parser {} full-mutation)
         e             (get result lookup)]
     (upsert! conn lookup e)
     e)))

;; Re-export of remote mutate
(def mutate!! remote/mutate!)

(defn mutate!!!
  ([ctx mut args]
   (mutate! ctx mut args)
   (remote/mutate! ctx mut args))
  ;; ---------------------------------------------
  ([ctx target mut args]
   (mutate! ctx target mut args)
   (remote/mutate! ctx target mut args))
  ;; ---------------------------------------------
  ([ctx lookup query mut args]
   (mutate! ctx lookup query mut args)
   (remote/mutate! ctx lookup query mut args)))

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
  (.-context react-component))

(defn- get-props
  [state]
  (or (-> state :rum/args first)
      {}))

(defn- props-or-opts
  [props opts k]
  (or (get props k)
      (get opts k)))

(defn- derive-lookup
  [props opts]
  (or (props-or-opts props opts :lookup)
      (let [idents        (:idents opts)
            mount-tx      (:mount-tx opts)
            assumed-props (merge props mount-tx)]
        (apply entity->lookup assumed-props idents))))

(defn- parse-state
  [state & [opts]]
  (let [ctx            (get-ctx state)
        props          (get-props state)
        derived-lookup {:lookup (derive-lookup props opts)}]
    (merge ctx opts derived-lookup {:props props})))

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
                                      query] :as ctx} (parse-state state opts)
                              rerender-fn #(rum/request-render react-component)]
                          (when mount-tx
                            (ds/transact! conn [mount-tx]))
                          (if lookup
                            (do (swap! registry add-fn-to-registry lookup rerender-fn)
                                (when (and freshen? lookup query)
                                  (remote/freshen! ctx lookup query))
                                (assoc state :rerender-fn rerender-fn))
                            state)))
   ;; -------------------------------------------------------------------------------
   :will-unmount       (fn will-unmount [state]
                         (let [{:keys [conn
                                       registry
                                       lookup]} (parse-state state opts )
                               fn-to-remove     (:rerender-fn state)]
                           (if (and lookup fn-to-remove)
                             (do
                               (swap! registry remove-fn-from-registry lookup fn-to-remove)
                               (when unmount-tx
                                 (ds/transact! conn unmount-tx))
                               (when (and auto-retract? lookup)
                                 (ds/transact! conn [[:db/retractEntity lookup]]))
                               (dissoc state :rerender-fn))
                             state)))
   ;; -------------------------------------------------------------------------------
   :before-render     (fn before-render [state]
                        (let [{:keys [conn
                                      parser
                                      lookup
                                      query
                                      props] :as ctx} (parse-state state opts)
                              data       (when (and lookup query)
                                           (q ctx))
                              upsert!    (partial upsert! conn lookup)
                              q          (partial q ctx)
                              q+         (partial q+ ctx)
                              ;;
                              freshen!   (partial freshen! ctx)
                              freshen!!  (partial freshen!! ctx)
                              freshen!!! (partial freshen!!! ctx)
                              ;;
                              mutate!    (partial mutate! ctx)
                              mutate!!   (partial mutate!! ctx)
                              mutate!!!  (partial mutate!!! ctx)
                              ;;
                              new-props  (cond-> props
                                           data   (assoc ::data data)
                                           lookup (assoc ::upsert! upsert!)
                                           true   (assoc ::q q)
                                           true   (assoc ::q+ q+)
                                           true   (assoc ::freshen! freshen!)
                                           true   (assoc ::freshen!! freshen!!)
                                           true   (assoc ::freshen!!! freshen!!!)
                                           true   (assoc ::mutate! mutate!)
                                           true   (assoc ::mutate!! mutate!!)
                                           true   (assoc ::mutate!!! mutate!!!)
                                           )]
                          (assoc state :rum/args [new-props])))
   })

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context initialization

(defn on-tx
  [registry {:keys [db-after tx-data]}]
  (let [affected-eids    (map :e tx-data)
        affected-lookups (apply eids->lookups db-after affected-eids)
        rerender-fns     (mapcat #(get @registry %)
                                 (set affected-lookups))]
    (doseq [rrf rerender-fns]
      (rrf))))

(defn- make-parser
  [conn {:keys [resolvers env] :as opts}]
  (p/parser
   {::p/env     (merge
                 {::p/reader               [p/map-reader
                                            pc/reader2
                                            pc/open-ident-reader
                                            p/env-placeholder-reader]
                  ::p/placeholder-prefixes #{">"}}
                 env)
    ::p/mutate  pc/mutate
    ::p/plugins [(pc/connect-plugin {::pc/register (or resolvers [])})
                 (pcd/datascript-connect-plugin {::pcd/conn conn})
                 p/error-handler-plugin
                 p/elide-special-outputs-plugin
                 p/trace-plugin]}))

(defn ropeid
  []
  (str (random-uuid)))

(defn- enrich-schema
  [schema]
  (merge schema
         {::id            {:db/unique :db.unique/identity}
          :db/ident       {:db/unique :db.unique/identity}
          :ui/freshening? {}
          :ui/mutating?   {}}))

(defn make-framework-context
  [{:keys [schema parser-opts remote] :as ctx}]
  (let [conn     (-> schema enrich-schema ds/create-conn)
        registry (atom {})
        ctx      {:conn        conn
                  :registry    registry
                  :parser      (or (:parser ctx)
                                   (make-parser conn parser-opts))
                  :parser-opts parser-opts
                  :remote      remote}]
    (ds/listen! conn ::listener (partial on-tx registry))
    (when (:ws-uri remote)
      (remote/install-websockets! ctx))
    ctx))

(defn ctx-provider
  [ctx & children]
  (let [provider      (.-Provider TightropeContext)
        props         #js {:value ctx}]
    (apply react/createElement provider props children)))
