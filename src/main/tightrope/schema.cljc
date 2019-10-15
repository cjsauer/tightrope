(ns tightrope.schema
  (:require [clojure.walk :as walk]))

(defn select-schema-keys
  "Prunes the keys of e to only include those defined in the given datascript schema.
  Acts recursively on map values."
  [ds-schema e]
  (let [schema-keys (conj (keys ds-schema) :db/id)
        sel-schema-keys #(select-keys % schema-keys)]
    (walk/postwalk (fn [m]
                     (if (map? m)
                       (sel-schema-keys m)
                       m))
                   e)))

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
