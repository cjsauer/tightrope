(ns tightrope.remote
  (:require [cljs-http.client :as http]
            [cljs.core.async :as a :refer [go go-loop <! >!]]
            [datascript.core :as ds]
            [goog.functions :as gfn]
            [chord.client :as chord]))

;; TODO: encoding should be set at the config level under the :remote key
(def ^:private http-params-key :edn-params)
(def ^:private http-accept-medium "application/edn")
(def ^:private max-batch-size 100)
(def ^:private batch-bin-size-ms 16) ;; roughly 1 frame

(def ^:private scheduled-posts-chan (a/chan max-batch-size))

(defn- post!
  [{:keys [remote] :as ctx} req]
  (go
    (let [req-middleware-fn  (get remote :request-middleware (fn [_ r] r))
          resp-middleware-fn (get remote :response-middleware (fn [_ r] r))
          mw-req             (req-middleware-fn ctx req)
          full-req           (update mw-req :headers merge {"Accept" http-accept-medium})
          resp               (<! (http/post (:uri remote) full-req))]
      (resp-middleware-fn ctx resp))))

(defn- batch-requests
  [reqs]
  (when-let [proto (first reqs)]
    (reduce (fn [p req]
              (update p http-params-key concat (get req http-params-key)))
            proto
            (rest reqs))))

(defn- fan-out-batched-response
  [resp chans]
  (go-loop [cs chans]
    (when-let [c (first cs)]
      (>! c resp)
      (a/close! c)
      (recur (next cs)))))

(defn- post-loop
  [ctx]
  (go-loop [reqs+retcs []]
    (let [tc (a/timeout batch-bin-size-ms)]
      (a/alt!
        tc        ([_]
                   (let [reqs        (map first reqs+retcs)
                         batched-req (batch-requests reqs)
                         resp        (<! (post! ctx batched-req))
                         chans       (map second reqs+retcs)]
                     (fan-out-batched-response resp chans)))
        scheduled-posts-chan ([r]
                              (recur (conj reqs+retcs r)))))))

(def ^:private debounced-post-loop
  (gfn/debounce post-loop batch-bin-size-ms))

(defn- schedule-post!
  [ctx req]
  (let [retc (a/chan)]
    (go
      (>! scheduled-posts-chan [req retc])
      (debounced-post-loop ctx))
    retc))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; query

(defn- handle-query-success
  [ctx lookup response]
  (let [{:keys [body]} response
        entity         (get body lookup)
        e-with-lookup  (conj entity lookup)]
    e-with-lookup))

(defn q
  ([ctx]
   (q ctx (:lookup ctx) (:query ctx)))
  ;;
  ([ctx target]
   (q ctx (:lookup target) (:query target)))
  ;;
  ([ctx lookup query]
   (go
     (let [full-query       [{lookup query}]
           req              {http-params-key full-query}
           {:keys [status]
            :as   response} (<! (schedule-post! ctx req))]
       (cond
         (< status 300) (handle-query-success ctx lookup response)
         :default       (throw (ex-info "Query responded with non-200 status"
                                        {:request  req
                                         :response response})))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; freshen

(defn freshen!
  ([ctx]
   (freshen! ctx (:lookup ctx) (:query ctx)))
  ;;
  ([ctx target]
   (freshen! ctx (:lookup target) (:query target)))
  ;;
  ([{:keys [conn] :as ctx} lookup query]
   (ds/transact! conn [(conj {:ui/freshening? true} lookup)])
   (go
     (let [entity             (<! (q ctx lookup query))
           freshen-retraction [:db.fn/retractAttribute lookup :ui/freshening?]]
       (ds/transact! conn [entity
                           freshen-retraction])
       entity))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; mutate

;; TODO: :ui/mutation? should be a set of currently in flight mutations,
;; i.e. :ui/in-flight-mutations

(defn- handle-mutation-success
  [{:keys [conn]} lookup m response]
  (let [{:keys [body]}      response
        entity              (get body m)
        entity-with-lookup  (conj entity lookup)
        mutating-retraction [:db.fn/retractAttribute lookup :ui/mutating?]]
    (ds/transact! conn [entity-with-lookup
                        mutating-retraction])
    entity-with-lookup))

(defn mutate!
  ([ctx mut args]
   (mutate! ctx (:lookup ctx) (:query ctx) mut args))
  ;;
  ([ctx target mut args]
   (mutate! ctx (:lookup target) (:query target) mut args))
  ;;
  ([{:keys [conn] :as ctx} lookup query mut args]
   (ds/transact! conn [(conj {:ui/mutating? true} lookup)])
   (go
     (let [full-mutation    [{`(~mut ~args) query}]
           req              {http-params-key full-mutation}
           {:keys [status]
            :as   response} (<! (schedule-post! ctx req))]
       (cond
         (< status 300) (handle-mutation-success ctx lookup mut response)
         :default       (throw (ex-info "Mutation responded with non-200 status"
                                        {:request  req
                                         :response response})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSockets

(def schan (atom nil))

(defn- ws-loop!
  [ctx server-chan]
  (reset! schan server-chan)
  (go-loop []
    (let [{:keys [message error]} (<! server-chan)]
      (when error
        (js/console.warn error))
      (when message
        (prn message)
        (recur)))))

(defn install-websockets!
  [{:keys [remote] :as ctx}]
  (set! (.-onload js/window)
        (fn []
          (go
            (let [{:keys [ws-channel error]} (<! (chord/ws-ch (:ws-uri remote)))]
              (println "tightrope: WebSocket connected!")
              (>! ws-channel "hello")
              (if error
                (js/console.error error)
                (ws-loop! ctx ws-channel)))))))
