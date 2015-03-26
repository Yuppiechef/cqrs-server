(ns cqrs-server.async-test
  (:require
   [clojure.core.async :as a]
   [schema.core :as s]
   [cqrs-server.async :as async]
   [cqrs-server.onyx :as onyx]
   [cqrs-server.cqrs :as cqrs]

   [onyx.plugin.core-async]
   [clojure.test :refer :all]
   [clojure.tools.logging :as log]))

;; A fully self-contained cqrs test, with in-memory zookeeper using only async channels.
;; This differs from the other tests in that it uses internal zookeeper - the rest use seperate
;; process (port 2181) zookeeper instance.

;; First, lets define the most basic module

(def users (atom {})) ;; Our super lightweight 'database'
(defn setup-aggregate-chan [chan]
  (a/go-loop []
    (when-let [msg (a/<! chan)]
      (doseq [u msg]
        (swap! users assoc (:name u) u))
      (recur))))

(cqrs/install-commands
  {:user/register {:name s/Str :age s/Int}})

;; :user/create
(defmethod cqrs/process-command :user/register [{:keys [data] :as c}]
  (if (get @users (:name data))
    (cqrs/events c 0 [[:user/register-failed data]])
    (cqrs/events c 1 [[:user/registered data]])))

(defmethod cqrs/aggregate-event :user/registered [{:keys [data] :as e}]
  [{:name (:name data)
    :age (:age data)}])

;; And that's it for the module, let's get cqrs setup.

(def env-config
  {:zookeeper/address "127.0.0.1:2185"
   :zookeeper/server? true
   :zookeeper.server/port 2185
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def peer-config
  {:zookeeper/address "127.0.0.1:2185"   
   :onyx.peer/inbox-capacity 100
   :onyx.peer/outbox-capacity 100
   :onyx.messaging/impl :http-kit-websockets
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def config
  {:command-stream (atom nil)
   :event-stream (atom nil)
   :event-store-stream (atom nil)
   :aggregator (atom nil)
   :feedback-stream (atom nil)
   :channels [:command-stream :event-stream :event-store-stream :aggregator :feedback-stream]})

(def catalog-map
  {:command/in-queue (async/stream :input (:command-stream config))
   :event/out-queue (async/stream :output (:event-stream config))
   :event/in-queue (async/stream :input (:event-stream config))
   :event/store (async/stream :fn (:event-store-stream config))
   :event/aggregator (async/stream :fn (:aggregator config))
   :command/feedback (async/stream :output (:feedback-stream config))})

(defn setup-env []
  (reset! users {})
  (doseq [c (:channels config)]
    (reset! (get config c) (a/chan 10)))

  (setup-aggregate-chan @(:aggregator config))
  
  (let [setup (cqrs/setup (java.util.UUID/randomUUID) catalog-map)]
    {:onyx (onyx/start setup env-config peer-config)}))

(defn stop-env [env]
  ((-> env :onyx :shutdown))
  
  (doseq [c (:channels config)]
    (swap! (get config c) (fn [chan] (a/close! chan) nil)))
  true)

(defn send-command [type data]
  (a/>!! @(:command-stream config) (cqrs/command "123" 1 type data)))

;; Need onyx dep to run
(defn run-test []
  (let [env (setup-env)
        _ (-> env :onyx :started-latch deref)
        event (delay (first (a/alts!! [@(:event-store-stream config) (a/timeout 2000)])))
        feedback (delay (first (a/alts!! [@(:feedback-stream config) (a/timeout 2000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (assert (= (:tp @event) :user/registered))
      (assert @feedback)
      (assert (= {"Bob" {:name "Bob" :age 33}} @users))
      
      (finally
        (stop-env env)))))
