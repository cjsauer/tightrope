(ns splitpea.server.db
  (:require [datomic.client.api :as d]
            [splitpea.model :as model]))

(defn entity-by
  [db k v & [pull-expr]]
  (d/pull db (or pull-expr '[*]) [k v]))

(defn xact
  [conn tx-data]
  (d/transact conn {:tx-data tx-data}))

(defn collaborators
  [db user-lookup & [pull-expr]]
  (flatten
   (d/q '[:find (pull ?member pull-expr)
          :in $ % [?ident ?val] pull-expr
          :where
          [?me ?ident ?val]
          (collaborators ?me ?member)]
        db
        model/rules
        user-lookup
        (or pull-expr '[*]))))
