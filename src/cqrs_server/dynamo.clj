(ns cqrs-server.dynamo
  (:require
   [taoensso.timbre :as log]
   [taoensso.faraday :as far]))

;; A sample of the dynamodb credentials.
(defn build-cred [access-key secret-key endpoint-region tablename]
  {:access-key access-key
   :secret-key secret-key
   :endpoint (str "dynamodb." endpoint-region ".amazonaws.com")
   :tablename tablename})

(defn table-setup
  "Setup dynamodb eventstore table

  We have a unique hashkey for each event - the id. That is a simple unordered uuid.
  Then we have an event-idx index against the event type and event date allowing us to query against a daterange of a selected type of event.
  In order to select all events across all types for a daterange, a full table scan is required.
  "
  [cred]
  (far/create-table
   cred (:tablename cred)
   [:id :s]
   {:gsindexes
    [{:name :event-idx :hash-keydef [:tp :s] :range-keydef [:dt :n] :projection [:id :tp :dt :t]
      :throughput {:read 1 :write 1}}]
    :throughput {:read 1 :write 1}
    :block? true}))

(defn writer [cred tbl e s]
  (log/info "Putting item" s " into " tbl " using cred " cred)
  (far/put-item cred tbl s)
  [e])

(defn lifecycle [task-map]
  {:onyx.core/params
   [writer
    (:dynamodb/config task-map)
    (:dynamodb/table task-map)]})

(defn catalog [cred]
  {:entry
   {:onyx/type :function
    :onyx/consumption :concurrent
    :dynamodb/table (:tablename cred)
    :dynamodb/config cred
    :onyx/batch-size 100
    :onyx/doc "Transacts segments to dynamodb"}
   :lifecycle lifecycle})


