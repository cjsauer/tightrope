(ns tightrope.client
  "Mount datascript entities to the UI"
  (:require ["react" :as react]
            [rum.core :as rum]
            [datascript.core :as ds]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.sugar]
            [tightrope.remote :as remote]
            [clojure.walk :as wlk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utilities

(defn entity->lookup
  [e & ks]
  (loop [k (first ks)]
    (when k
      (if (contains? e k)
        [k (get e k)]
        (recur (first (next ks)))))))

(defn eids->lookups
  [db & eids]
  (ds/q '[:find ?attr ?value
          :in $ [[?attr [[?aprop ?avalue] ...]] ...] [?eids ...]
          :where
          [(= ?avalue :db.unique/identity)]
          [?eids ?attr ?value]]
        db (:schema db) eids))

(defn inject-known-lookups
  [db e]
  (if-let [eid (:db/id e)]
    (let [lookups (eids->lookups db eid)]
      (into e lookups))
    e))

(defn inject-all-known-lookups
  [db e]
  (wlk/postwalk (partial inject-known-lookups db) e))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; App state helpers

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; query

(defn q
  ([ctx]
   (q ctx (:lookup ctx) (:query ctx)))
  ;; ---------------------------------------------
  ([ctx target]
   (q ctx (:lookup target) (:query target)))
  ;; ---------------------------------------------
  ([{:keys [conn parser]} lookup query]
   (let [db              (ds/db conn)
         pull-result     (try-pull db query lookup)
         full-query      [{lookup query}]
         parse-env       (cond-> {:conn conn}
                           pull-result (assoc ::p/entity {lookup pull-result}))
         parse-result    (parser parse-env full-query)
         data            (get parse-result lookup)]
     (inject-all-known-lookups db data))))

;; Re-export of remote query
(def q+ remote/q)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; freshen

(defn freshen!
  ([ctx]
   (let [q (q ctx)]
     (upsert! (:conn ctx) (:lookup ctx) q)
     q))
  ;; ---------------------------------------------
  ([ctx target]
   (let [q (q ctx target)]
     (upsert! (:conn ctx) (:lookup target) q)
     q))
  ;; ---------------------------------------------
  ([ctx lookup query]
   (let [q (q ctx lookup query)]
     (upsert! (:conn ctx) lookup q)
     q)))

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
                            (ds/transact! conn mount-tx))
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
                                      props] :as ctx} (parse-state state opts)
                              data       (q ctx)
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

(defn- default-parser
  [{:keys [resolvers env] :as opts}]
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
                 p/error-handler-plugin
                 p/elide-special-outputs-plugin
                 p/trace-plugin]}))

(defn ropeid
  []
  (str (random-uuid)))

(defn- enrich-schema
  [schema]
  (assoc schema ::id {:db/unique :db.unique/identity}))

(defn make-framework-context
  [{:keys [schema parser-opts remote] :as ctx}]
  (let [conn     (-> schema enrich-schema ds/create-conn)
        registry (atom {})]
    (ds/listen! conn ::listener (partial on-tx registry))
    {:conn     conn
     :registry registry
     :parser   (or (:parser ctx)
                   (default-parser parser-opts))
     :remote   remote
     }))

(defn ctx-provider
  [ctx & children]
  (let [provider      (.-Provider TightropeContext)
        props         (clj->js {:value ctx})]
    (apply react/createElement provider props children)))
