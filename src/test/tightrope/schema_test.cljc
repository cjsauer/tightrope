(ns tightrope.schema-test
  (:require [tightrope.schema :as rope-schema]
            [clojure.test :refer [deftest is]]))

(def datomic-schema
  [{:db/ident       :user/name
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string}

   {:db/ident       :user/widgets
    :db/isComponent true
    :db/cardinality :db.cardinality/many
    :db/valueType   :db.type/ref}

   {:db/ident       :widget/id
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one
    :db/valueType   :db.type/string}
   ])

(deftest datomic-to-datascript-test
  (is (= (rope-schema/datomic->datascript datomic-schema)
         {:user/name #:db{:unique :db.unique/identity}
          :user/widgets #:db{:isComponent true
                             :cardinality :db.cardinality/many
                             :valueType :db.type/ref}
          :widget/id #:db{:unique :db.unique/identity}})))
