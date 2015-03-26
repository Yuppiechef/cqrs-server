(ns cqrs-server.simple-async-test
  (:require
   [clojure.core.async :as a]
   [schema.core :as s]
   [cqrs-server.simple :as simple]
   [cqrs-server.async :as async]
   [cqrs-server.cqrs :as cqrs]

   [clojure.test :refer :all]
   [taoensso.timbre :as log]))

;; This test uses the simple mode that emulates onyx, but is completely non-distributed.

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

(def config
  {:command-stream (atom nil)
   :event-stream (atom nil)
   :event-store-stream (atom nil)
   :aggregator (atom nil)
   :feedback-stream (atom nil)
   :error-stream (atom nil)
   :channels [:command-stream :event-stream :event-store-stream :aggregator :feedback-stream :error-stream]})

(def catalog-map
  {:command/in-queue (async/stream :input (:command-stream config))
   :event/out-queue (async/stream :output (:event-stream config))
   :event/in-queue (async/stream :input (:event-stream config))
   :event/store (async/stream :fn (:event-store-stream config))
   :event/aggregator (async/stream :fn (:aggregator config))
   :command/feedback (async/stream :output (:feedback-stream config))
   :cqrs/error (async/stream :fn (:error-stream config))})

(defn setup-env []
  (reset! users {})
  (doseq [c (:channels config)]
    (reset! (get config c) (a/chan 10)))

  (setup-aggregate-chan @(:aggregator config))
  
  (let [setup (cqrs/setup (java.util.UUID/randomUUID) catalog-map)]
    {:simple (simple/start setup)}))

(defn stop-env [env]
  (doseq [c (:channels config)]
    (swap! (get config c) (fn [chan] (a/close! chan) nil)))
  true)

(defn send-command [type data]
  (a/>!! @(:command-stream config) (cqrs/command "123" 1 type data)))

(deftest run-test []
  (let [env (setup-env)
        event (delay (first (a/alts!! [@(:event-store-stream config) (a/timeout 2000)])))
        feedback (delay (first (a/alts!! [@(:feedback-stream config) (a/timeout 2000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (assert (= (:tp @event) :user/registered))
      (assert @feedback)
      (assert (= {"Bob" {:name "Bob" :age 33}} @users))
      
      (finally
        (stop-env env)))))

(deftest run-error-test []
  (let [env (setup-env)
        event (delay (first (a/alts!! [@(:event-store-stream config) (a/timeout 500)])))
        feedback (delay (first (a/alts!! [@(:feedback-stream config) (a/timeout 500)])))
        error (delay (first (a/alts!! [@(:error-stream config) (a/timeout 2000)])))]
    (try
      (send-command :user/invalid-command {:name "Bob" :age 33})
      (assert (= (-> @error :error/opts :tag) :command-coerce))
      (assert (= (-> @error :error/opts :data :cmd :ctp) :user/invalid-command))
      (assert (= (-> @error :error/level) :dev))
      (assert (nil? @event))
      (assert (nil? @feedback))
      (finally
        (stop-env env)))))
