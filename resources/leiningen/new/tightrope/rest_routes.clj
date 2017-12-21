(ns {{namespace}}.rest-routes
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [hiccup.core :as hiccup]
            [ring.middleware.defaults :refer [api-defaults wrap-defaults]]
            [ring.util.response :refer [content-type response]]))

(defn home-view
  [req]
  [:h1 "Hello, tightrope!"])

(def not-found-view
  [:p "Oops! The page you're looking for doesn't exist!"])

(defn render
  [hiccup-form]
  (-> hiccup-form
      hiccup/html
      response
      (content-type "text/html")))

(defroutes app-routes
  (GET "/" [req] (render (home-view req)))
  (route/resources "/")
  (route/not-found (render not-found-view)))

(defn app-handler
  []
  (-> #'app-routes
      (wrap-defaults api-defaults)))
