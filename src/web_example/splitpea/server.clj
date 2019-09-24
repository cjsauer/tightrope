(ns splitpea.server
  (:require [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [clojure.core.async :as a :refer [<!!]]
            [datascript.core :as ds]))

(defonce conn (ds/create-conn {:person/id   {:db/unique :db.unique/identity}
                               :person/name {:db/unique :db.unique/identity}}))


(pc/defresolver db-resolver [{::keys [conn]} _]
  {::pc/output [::db]}
  {::db (ds/db conn)})


(def person-keys [:person/id
                  :person/name
                  :person/age])


(pc/defresolver person-by-id-resolver [_ {::keys [db]
                                    :keys  [person/id]}]
  {::pc/input  #{::db :person/id}
   ::pc/output person-keys}
  (ds/pull db '[*] [:person/id id]))


(pc/defresolver person-by-name-resolver [_ {::keys [db]
                                            :keys  [person/name]}]
  {::pc/input  #{::db :person/name}
   ::pc/output person-keys}
  (ds/pull db '[*] [:person/name name]))


(pc/defresolver all-people-resolver [_ {::keys [db]}]
  {::pc/input #{::db}
   ::pc/output [{::all-people [:person/id]}]}
  {::all-people
   (->> db
        (ds/q '[:find [?pid ...]
                :where [_ :person/id ?pid]])
        (map #(hash-map :person/id %)))})


(pc/defmutation upsert-person [{::keys [conn]} person]
  {::pc/sym    'upsert-person
   ::pc/params [:person/name :person/age]
   ::pc/output [::db :person/id]}
  (let [{:keys [person/id] :as new-person}
        (merge
         {:person/id (str (java.util.UUID/randomUUID))}
         (select-keys person [:person/id :person/name :person/age]))]
    {::db       (:db-after (ds/transact! conn [new-person]))
     :person/id id}))


(def my-app-registry [db-resolver
                      person-by-id-resolver
                      person-by-name-resolver
                      all-people-resolver
                      upsert-person])


(def parser
  (p/parallel-parser
   {::p/env     {::p/reader               [p/map-reader
                                           pc/parallel-reader
                                           pc/open-ident-reader
                                           p/env-placeholder-reader]
                 ::p/placeholder-prefixes #{">"}
                 ::conn conn}
    ::p/mutate  pc/mutate-async
    ::p/plugins [(pc/connect-plugin {::pc/register my-app-registry})
                 p/error-handler-plugin
                 p/request-cache-plugin
                 p/trace-plugin]}))


(comment

  (<!! (parser {} [{'(upsert-person {:person/name "Calvin" :person/age 28})
                    [:person/id :person/name :person/age]}]))

  (<!! (parser {} [{[:person/name "Calvin"] [:person/age :person/id]}]))

  (<!! (parser {} [{::all-people [:person/id]}]))

  )
