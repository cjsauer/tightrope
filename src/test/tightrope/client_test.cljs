(ns tightrope.client-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [tightrope.client :as rope]
            [com.wsscode.pathom.connect :as pc]
            [datascript.core :as ds]))

(pc/defresolver greeting-resolver
  [_ {:user/keys [handle]}]
  {::pc/input  #{:user/handle}
   ::pc/output #{:user/greeting}}
  (let [greeting (str "Welcome, " handle "!")]
    {:user/greeting greeting}))

(def ^:dynamic *app-ctx*)

(defn app-ctx-fixture
  [f]
  (binding [*app-ctx* (rope/make-framework-context
                       {:schema      {:user/handle      {:db/unique :db.unique/identity}
                                      :user/birth-name  {:db/unique :db.unique/identity}
                                      :user/power-level {}
                                      :user/friends      {:db/valueType   :db.type/ref
                                                          :db/cardinality :db.cardinality/many}}
                        :parser-opts {:resolvers [greeting-resolver]}
                        :remote      {}})]
    (ds/transact! (:conn *app-ctx*) [{:user/handle      "goku"
                                      :user/birth-name  "kakarot"
                                      :user/power-level 9001
                                      :user/friends "1"}
                                     {:db/id "1"
                                      :user/handle "krillin"}])
    (f)))

(use-fixtures :each app-ctx-fixture)

(deftest automatic-datascript-resolution
  (let [result (rope/q *app-ctx* [:user/handle "goku"] [:user/power-level])]
    (is (= 9001
           (:user/power-level result)))))

(deftest resolvers-can-derive-from-datascript
  (let [result (rope/q *app-ctx* [:user/handle "goku"] [:user/greeting])]
    (is (= "Welcome, goku!"
           (:user/greeting result)))))

(deftest upsertion-is-the-new-data-plus-lookup
  (is (= (rope/upsertion [:user/handle "goku"] {:user/appetite :insatiable})
         {:user/handle     "goku"
          :user/appetite   :insatiable})))

(deftest entity-ids->lookup-set
  (is (= (rope/eids->lookups (-> *app-ctx* :conn ds/db) 1)
         #{[:user/birth-name "kakarot"]
           [:user/handle "goku"]})))

(def pull-resolverless #'tightrope.client/pull-resolverless)

(deftest pull-resolverless-includes-only-keys-not-output-by-resolvers
  (ds/transact! (:conn *app-ctx*) [{:user/handle "goku"}
                                   {:user/handle "krillin"}])
  (is (= (pull-resolverless *app-ctx*
                            (-> *app-ctx* :conn ds/db)
                            '[:user/greeting
                              {:user/friends [:user/handle :user/greeting]}]
                            1)
         {:user/friends [{:user/handle "krillin"}]})))
