(ns splitpea.model
  (:require [clojure.set :as cset]
            [tightrope.schema :as rope-schema]
            #?(:clj  [datomic.client.api :as d])
            #?(:cljs [datascript.core :as d])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Essential state

(def user-attrs
  [{:db/ident        :user/validate
    :db.entity/attrs [:user/email]}

   {:db/ident       :user/email
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc         "Uniquely identifying email addresses of a user, and the primary means of authentication"}
   ])

(def team-attrs
  [{:db/ident        :team/validate
    :db.entity/attrs [:team/slug :team/members]
    :db.entity/preds `team-dag?}

   {:db/ident       :team/slug
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc         "Unique, URL-safe identifier of a team"}

   {:db/ident       :team/members
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Users and (sub)teams that make up this team."}
   ])

(def idea-attrs
  [{:db/ident        :idea/validate
    :db.entity/attrs [:idea/author :idea/instant :idea/content :idea/subject]}

   {:db/ident       :idea/author
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "User that shared this idea"}

   {:db/ident       :idea/instant
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Instant of time that a user shared an idea"}

   {:db/ident       :idea/coordinate
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:idea/instant :idea/author]
    :db/cardinality :db.cardinality/one
    :db/doc         "Uniquely identifying [instant user-eid] of an idea. Coordinate in thought-space."}

   {:db/ident       :idea/content
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "User-entered content of an idea"}

   {:db/ident       :idea/subject
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Entity being discussed or described by a particular idea. Most commonly another idea."}
   ])

(def media-attrs
  [{:db/ident        :media/validate
    :db.entity/attrs [:media/url]}

   {:db/ident       :media/url
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "URL of an external media resource"}
   ])

(def essential-state
  (cset/union user-attrs
              team-attrs
              idea-attrs
              media-attrs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Derivations

(def rules
  '[

    [(all-team-members [?team] ?member)
     [?team :team/members ?member]]
    [(all-team-members [?team] ?member)
     [?team :team/members ?subteam]
     (all-team-members ?subteam ?member)]

    [(all-team-users [?team] ?u)
     [?team :team/members ?u]
     [?u :user/email]]
    [(all-team-users [?team] ?u)
     [?team :team/members ?subteam]
     (all-team-users ?subteam ?u)]

    [(collaborators [?me] ?collaborator)
     [?team :team/members ?me]
     (all-team-users ?team ?collaborator)
     [(not= ?me ?collaborator)]]

    ;; "Top level" ideas
    ;;   - Ones in which the subject is not itself another idea
    ;;   - Can be constrained to a specific team

    ;; What are ideas without a subject?
    ;; Is it okay to not have a subject?

    ;; Reddit style:
    ;;   - Subject can be a link
    ;;   - Subject can be the org itself (text post)
    ;;   - Users vote on priority (but have fixed number of votes that can be in play)

    ])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constraints

(defn team-dag?
  "Ensures that a team cannot be a member (or sub-member) of itself,
  thus enforcing a directed acylic graph upon the :team/members relation."
  [db eid]
  (empty?
   (d/q '[:find ?member
          :in $ % ?team ?member
          :where
          (all-team-members ?team ?member)]
        db rules eid eid)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema (implementation)

(def datomic-schema
  essential-state)

(def datascript-schema
  (rope-schema/datomic->datascript datomic-schema))
