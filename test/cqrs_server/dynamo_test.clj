(ns cqrs-server.dynamo-test
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :as ds :refer [schema fields part]]
   [schema.core :as s]
   [cqrs-server.cqrs :as cqrs]
   
   [onyx.api]
   [onyx.plugin.core-async]
   
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [clojure.tools.logging :as log]
   [taoensso.faraday :as far]
   [taoensso.nippy :as nippy]
   
   [cqrs-server.async :as async]
   [cqrs-server.onyx :as onyx]
   [cqrs-server.dynamo :as dynamo]))


;; Simple testing module 
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

;; Implementation

(def local-cred
  {:access-key "aws-access-key"
   :secret-key "aws-secret-key"
   :endpoint   "http://localhost:8000"
   :tablename :eventstore})


(def env-config
  {:zookeeper/address "127.0.0.1:2181"
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def peer-config
  {:zookeeper/address "127.0.0.1:2181"
   :onyx.peer/inbox-capacity 100
   :onyx.peer/outbox-capacity 100
   :onyx.messaging/impl :http-kit-websockets
   :onyx.peer/job-scheduler :onyx.job-scheduler/round-robin})

(def config
  {:command-stream (atom nil)
   :event-stream (atom nil)
   :aggregator (atom nil)
   :feedback-stream (atom nil)
   :channels [:command-stream :event-stream :aggregator :feedback-stream]})



(def catalog-map
  {:command/in-queue (async/stream :input (:command-stream config))
   :event/out-queue (async/stream :output (:event-stream config))
   :event/in-queue (async/stream :input (:event-stream config))
   :event/store (dynamo/catalog local-cred)
   :event/aggregator (async/stream :fn (:aggregator config))
   :command/feedback (async/stream :output (:feedback-stream config))})

(defn setup-env []
  (dynamo/table-setup local-cred)
  
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
  
  (try
    (far/delete-table local-cred (:tablename local-cred))
    (catch Exception e nil))
  
  true)

(defn command [type data]
  (cqrs/command "123" 1 type data))

(defn send-command [type data]
  (a/>!! @(:command-stream config) (command type data)))

;; Needs onyx, zookeeper & dynamodb local to run
(defn run-test []
  (let [env (setup-env)
        _ (-> env :onyx :started-latch deref)
        feedback (delay (first (a/alts!! [@(:feedback-stream config) (a/timeout 2000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (assert @feedback)
      (assert (= {"Bob" {:name "Bob" :age 33}} @users))
      (assert (= 1 (count (far/scan local-cred :eventstore))))
      (assert (= {:age 33 :name "Bob"} (nippy/thaw (:data (first (far/scan local-cred :eventstore))))))
      
      (finally
        (stop-env env)))))
