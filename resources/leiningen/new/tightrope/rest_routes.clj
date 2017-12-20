(ns {{namespace}}.rest-routes
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [hiccup.core :as hiccup]))

(defn home-handler
  [req]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body
   (hiccup/html
    [:h1 "Hello, tightrope!"])})

(defn not-found-handler
  []
  (hiccup/html
   [:p "Oops! The page you're looking for doesn't exist!"]))

(defroutes app-routes
  (GET "/" [req] (home-handler req))
  (route/resources "/")
  (route/not-found (not-found-handler)))
