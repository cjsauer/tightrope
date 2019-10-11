(ns splitpea.server.db
  (:require [datomic.client.api :as d]
            [splitpea.model :as model]))

(def cfg {:server-type :ion
          :region "us-east-1"
          :system "splitpea-dev"
          :creds-profile "sandbox"
          :endpoint "http://entry.splitpea-dev.us-east-1.datomic.net:8182/"
          :proxy-port 8182})

(def get-client
  "This function will return a local implementation of the client
  interface when run on a Datomic compute node. If you want to call
  locally, fill in the correct values in the map."
  (memoize #(d/client cfg)))

(defn- has-ident?
  [db ident]
  (contains? (d/pull db {:eid ident :selector [:db/ident]})
             :db/ident))

(defn- schema-loaded?
  [db]
  (has-ident? db (-> model/datomic-schema first :db/ident)))

(defn- load-schema
  [conn]
  (let [db (d/db conn)]
    (if (schema-loaded? db)
      :already-loaded
      (d/transact conn {:tx-data model/datomic-schema}))))

(defn ensure-dataset
  "Ensure that a database named db-name exists, running setup-fn
  against a connection. Returns connection"
  [db-name setup-sym]
  (require (symbol (namespace setup-sym)))
  (let [setup-var (resolve setup-sym)
        client (get-client)]
    (when-not setup-var
      (throw (ex-info (str "Could not resolve " setup-sym))))
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})
          db (d/db conn)]
      (setup-var conn)
      conn)))

(defn get-conn
  []
  (ensure-dataset "splitpea-dev-db" `load-schema))

(defn get-db
  []
  (d/db (get-conn)))

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

(comment

  (defn load-sample-data
    []
    (let [tx-data [{:db/ensure    :team/validate
                    :team/slug    "blue-team"
                    :team/members [{:user/email "another"}
                                   {:user/email "calvin"}
                                   {:user/email "brittany"}]}
                   {:db/ensure    :team/validate
                    :team/slug    "red-team"
                    :team/members [{:user/email "derek"}]
                    }]]
      (d/transact (get-conn) {:tx-data tx-data})
      ))

  (load-sample-data)

  (entity-by (get-db) :user/email "calvin")

  (entity-by (get-db) :team/slug "red-team")

  (entity-by (get-db) :team/slug "blue-team" '[* {:team/members [*]}])

  (xact (get-conn) [[:db/retract [:team/slug "red-team"]
                     :team/members [:user/email "calvin"]]])

  (collaborators (get-db) [:user/email "calvin"] [:user/email])



  (d/delete-database (get-client) {:db-name "splitpea-dev-db"})

  )
