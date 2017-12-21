(ns {{namespace}}.rest-routes
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [hiccup.core :as hiccup]
            [hiccup.page :refer [html5 include-css include-js]]
            [ring.middleware.defaults :refer [api-defaults wrap-defaults]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ring.util.response :refer [content-type response]]))

(defn home-view
  [req]
  (html5
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    (include-css "css/style.css")]
   [:body
    [:h1 "Hello, tightrope!"]
    (include-js "js/compiled/example-app.js")
    [:script {:type "text/javascript"}
     "{{namespace-path}}.core.bootstrap();"]]))

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
      (wrap-defaults api-defaults)
      (wrap-gzip)))
