(ns cqrs-server.cqrs
  (:require
   [cqrs-server.util :as util]
   [clojure.core.async :as a]
   [taoensso.timbre :as log]
   [taoensso.nippy :as nippy]
   [schema.core :as s]
   [schema.coerce :as coerce]
   [clj-uuid :as u]))

;; TODO - tag code version onto the events.

(defn error
  "Produce an error segment, level should be one of :dev, :user, :system.

  :dev - code bug, send to DLQ
  :user - user error, provide feedback
  :system - system error (network problems, file writing issues, etc), apply retry strategy and DLQ if not"
  [level msg & {:keys [tag data] :as opts}]
  {::error/msg msg
   ::error/opts opts
   ::error/level level})

(defmulti aggregate-event (fn [{:keys [tp] :as event}] tp))

(defmethod aggregate-event :default [_] [])

(defn aggregate-event* [writer-fn & args]
  (let [e (last args)
        _ (log/info "Aggregating" e)
        r (aggregate-event e)]
    (apply writer-fn (concat (butlast args) [e r]))))

(defmulti command-coerce (fn [{:keys [tp] :as command}] tp))

(defmethod command-coerce :default [{:keys [tp]}]
  [(error :dev "command-coerce for " tp " has not been defined.")])

(defn command-coerce* [c]
  (log/info "Coercing: " c)
  (let [coerced (command-coerce c)]
    (if (:error coerced)
      [(error :user "Command validation error" :tag :validation :data (:error coerced))]
      [(assoc c :data coerced)])))


(defn install-command [[type schema]]
  `(let [coercer# (coerce/coercer (assoc ~schema s/Any s/Any) util/coercion-matcher)]
     (defmethod command-coerce ~type [c#]
       (coercer# (:data c#)))))

(defmacro install-commands [commands]
  (concat [(symbol "do")] (map install-command commands)))



(defmulti process-command (fn [{:keys [tp] :as command}] tp))

(defmethod process-command :default [{:keys [tp]}]
  [(error :dev "process-command for " tp " has not been defined.")])

(defn process-command* [& args]
  (let [command (last args)
        _ (log/info "Processing Command: " command)
        _ (log/info "args: " args)
        kpairs (butlast args)
        result
        (process-command (if (-> kpairs count zero?) command (apply assoc command (butlast args))))]
    (log/info "Processed Command: " result)
    result))

(defn event-store [& [writer-fn & args :as a]]
  (log/info "EVENT STORE: " a)
  (let [e (last args)
        _ (log/info "Event: " e)
        s (assoc e :id (str (:id e)) :cid (str (:cid e)) :data (nippy/freeze (:data e)))]
    (log/info "Prepared for storage: " s)
    (apply writer-fn (concat (butlast args) [e s]))))



(defn command [basis-t type data]
  {:t basis-t
   :tp type
   :id (str (java.util.UUID/randomUUID))
   :data data})

(defn event [command segment n [type data]]
  {:id (u/v5 u/+namespace-oid+ (str (:id command) ":" n "/" segment))
   :tp type
   :cid (:id command)
   :ctp (:tp command)
   :dt (.getTime (java.util.Date.))
   :t (:t command) 
   :data data})

(defn events [command segment msgs]
  (map (partial event command segment) (range) msgs))

(def command-workflow
  [[:command/in-queue :command/coerce]
   [:command/coerce :command/process]
   [:command/process :event/out-queue]
   [:event/in-queue :event/store]
   [:event/store :event/aggregator]
   [:event/aggregator :command/feedback]])


(comment
  ;; Failure handling
  [[:command/coerce :error]
   [:command/process :error]
   [:event/store :error]
   [:event/aggregator :error]
   [:event/aggregate-store :error]])


(defn catalog
  [c]
  [(assoc
    (-> c :command/in-queue :entry)
    :onyx/name :command/in-queue
    :onyx/type :input
    :onyx/consumption :concurrent
    :onyx/batch-size 1)

   {:onyx/name :command/coerce
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/command-coerce*
    :onyx/consumption :concurrent
    :onyx/batch-size 1}

   (assoc
    (-> c :command/process :entry)
    :onyx/name :command/process
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/process-command*
    :onyx/consumption :concurrent
    :onyx/batch-size 1)

   (assoc
    (-> c :event/out-queue :entry)
    :onyx/name :event/out-queue
    :onyx/type :output
    :onyx/consumption :concurrent
    :onyx/batch-size 1)

   (assoc
    (-> c :event/in-queue :entry)
    :onyx/name :event/in-queue
    :onyx/type :input
    :onyx/consumption :concurrent
    :onyx/batch-size 1)
   
   (assoc
    (-> c :event/store :entry)
    :onyx/name :event/store
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/event-store
    :onyx/consumption :concurrent
    :onyx/batch-size 1)

   (assoc
    (-> c :event/aggregator :entry)
    :onyx/name :event/aggregator
    :onyx/type :function
    :onyx/fn :cqrs-server.cqrs/aggregate-event*
    :onyx/consumption :concurrent
    :onyx/batch-size 1)

   (assoc
    (-> c :command/feedback :entry)
    :onyx/name :command/feedback
    :onyx/type :output
    :onyx/consumption :concurrent
    :onyx/batch-size 1)])

(defn error-flows [workflow catalog]
  (map
   (fn [c]
     {:flow
      {:flow/from (:onyx/name c)
       :flow/to :cqrs/error
       :flow/thrown-exception? true
       :flow/short-circuit? true
       :flow/post-transform :cqrs-server.cqrs/error}})
   catalog))

(defn setup [onyxid catalog-map]
  {:catalog (catalog catalog-map)
   :workflow command-workflow
   :onyxid onyxid
   :catmap catalog-map})


