(ns tightrope.schema)

(defn select-schema-keys
  [ds-schema e]
  (select-keys e
               (conj (keys ds-schema) :db/id)))

(defn datomic->datascript
  "Converts a datomic schema into its equivalent datascript schema."
  [schema]
  (when-let [schema-kvs (seq (zipmap (map :db/ident schema) schema))]
    (letfn [(select-compat [[k {:db/keys [valueType unique cardinality isComponent]}]]
              [k (cond-> {}
                   unique                               (assoc :db/unique unique)
                   (some? isComponent)                  (assoc :db/isComponent isComponent)
                   (= :db.cardinality/many cardinality) (assoc :db/cardinality cardinality)
                   (= :db.type/ref valueType)           (assoc :db/valueType valueType))])]
      (into {}
            (map select-compat)
            schema-kvs))))
