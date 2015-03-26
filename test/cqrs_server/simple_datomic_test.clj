(ns cqrs-server.datomic-test
  (:require
   [datomic.api :as d]
   [datomic-schema.schema :as ds :refer [schema fields part]]
   [schema.core :as s]
   
   [clojure.core.async :as a]
   [clojure.test :refer :all]
   [taoensso.timbre :as log]
   [taoensso.faraday :as far]
   [taoensso.nippy :as nippy]
   
   [cqrs-server.cqrs :as cqrs]
   [cqrs-server.simple :as simple]
   [cqrs-server.async :as async]
   [cqrs-server.datomic :as datomic]
   [cqrs-server.dynamo :as dynamo]))


;; Simple testing module 
;; First, lets define the most basic module
(def db-schema
  [(schema
       base
       (fields
        [uuid :uuid :unique-identity]))
   (schema
    user
    (fields
     [name :string :indexed :unique-identity]
     [age :long]))])

(cqrs/install-commands
  {:user/register {:name s/Str :age s/Int}})

;; :user/create
(defmethod cqrs/process-command :user/register [{:keys [data] :as c}]
  (if (first (d/q '[:find ?e :in $ ?name :where [?e :user/name ?name]] (datomic/command-db c) (:name data)))
    (cqrs/events c 0 [[:user/register-failed data]])
    (cqrs/events c 1 [[:user/registered data]])))

(defmethod cqrs/aggregate-event :user/registered [{:keys [data] :as e}]
  [{:db/id (d/tempid :db.part/user)
    :user/name (:name data)
    :user/age (:age data)}])

;; Implementation

(def local-cred
  {:access-key "aws-access-key"
   :secret-key "aws-secret-key"
   :endpoint   "http://localhost:8000"
   :tablename :eventstore})

(def config
  {:command-stream (atom nil)
   :event-stream (atom nil)
   :aggregator (atom nil)
   :feedback-stream (atom nil)
   :channels [:command-stream :event-stream :aggregator :feedback-stream]
   :datomic-uri "datomic:mem://cqrs"})


(def catalog-map
  {:command/in-queue (async/stream :input (:command-stream config))
   :command/process (datomic/command-processor (:datomic-uri config))
   :event/out-queue (async/stream :output (:event-stream config))
   :event/in-queue (async/stream :input (:event-stream config))
   :event/store (dynamo/catalog local-cred)
   :event/aggregator (datomic/aggregate-stream (:datomic-uri config))
   :command/feedback (async/stream :output (:feedback-stream config))})

(defn setup-env [db-schema]
  (dynamo/table-setup local-cred)
  
  (d/create-database (:datomic-uri config))
  (d/transact
   (d/connect (:datomic-uri config))
   (ds/generate-schema (concat datomic/db-schema db-schema)))
  
  (doseq [c (:channels config)]
    (reset! (get config c) (a/chan 10)))

  (let [setup (cqrs/setup (java.util.UUID/randomUUID) catalog-map)]
    {:simple (simple/start setup)}))

(defn stop-env [env]
  (doseq [c (:channels config)]
    (swap! (get config c) (fn [chan] (a/close! chan) nil)))

  (d/delete-database (:datomic-uri config))
  
  (try
    (far/delete-table local-cred (:tablename local-cred))
    (catch Exception e nil))
  
  true)

(defn send-command [type data]
  (a/>!! @(:command-stream config) (cqrs/command (d/basis-t (d/db (d/connect (:datomic-uri config)))) type data)))

(deftest run-test []
  (let [env (setup-env db-schema)
        feedback (delay (first (a/alts!! [@(:feedback-stream config) (a/timeout 2000)])))]
    (try
      (send-command :user/register {:name "Bob" :age 33})
      (assert @feedback)
      (assert (= 1 (count (far/scan local-cred :eventstore))))
      (assert (= {:age 33 :name "Bob"} (nippy/thaw (:data (first (far/scan local-cred :eventstore))))))
      (assert (= #{["Bob" 33]} (d/q '[:find ?n ?a :where [?e :user/name ?n] [?e :user/age ?a]] (d/as-of (d/db (d/connect (:datomic-uri config))) (:ta @feedback)))))
      (finally
        (stop-env env)))))
