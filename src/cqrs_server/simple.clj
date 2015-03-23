(ns cqrs-server.simple
  (:require
   [taoensso.timbre :as log]
   [clojure.core.async :as a]
   [clojure.java.data :as data]))

;; TODO: Flow conditions do not support short-circuiting, #{:all :none} to or exception handling


(defn resolve-fn [k]
  (cond
    (fn? k) k
    (keyword? k) (resolve (symbol (namespace k) (name k)))
    :else (throw (RuntimeException. "Unsupported function value: " k))))

(defn apply-function [f p x]
  (let [segments (apply f (concat p [x]))]
    (cond
      (map? segments) [segments]
      (coll? segments) segments
      :else [segments])))

(defmulti find-medium :onyx/medium)
(defmethod find-medium :default [_] nil)

(defmulti find-type :onyx/type)
(defmethod find-type :default [_] nil)

(defprotocol Task
  (setup [this inchan outchan]))

(deftype FunctionTask [task-map]
  Task
  (setup [this inchan outchan]
    (let [f (or (:onyx.core/fn task-map) (resolve-fn (:onyx/fn task-map)))
          p (or (:onyx.core/params task-map) (map (partial get task-map) (:onyx/params task-map)))]
      (a/pipeline 1 outchan (mapcat (partial apply-function f p)) inchan true (:simple/ex-handler task-map)))))

(defmethod find-type :function [_] ->FunctionTask)

(deftype AsyncTask [task-map]
  Task
  (setup [this inchan outchan]
    (let [in (case (:onyx/type task-map) :input (:core.async/chan task-map) inchan)
          out (case (:onyx/type task-map) :output (:core.async/chan task-map) outchan)]
      (a/pipeline 1 out (map identity) in true (:simple/ex-handler task-map)))))



(defmethod find-medium :core.async [_] ->AsyncTask)

(defn construct-task [env task]
  (let [name (:onyx/name task)
        lifecycle-fn (get-in env [:lifecycle name] (fn [_] {}))
        task (merge task (lifecycle-fn task))
        ctor (or (find-medium task) (find-type task))
        in-chan (a/chan 100)
        out-chan (a/chan 100)]
    (log/info "Constructing task:" (:onyx/name task) "-" task)
    {:name (:onyx/name task)
     :task (setup (ctor task) in-chan out-chan)
     :taskmap task
     :edges {:in in-chan :out out-chan :out-mult (a/mult out-chan)}}))

(defn construct-catalog [env catalog]
  (into {} (map (fn [t] [(:onyx/name t) (construct-task env t)]) catalog)))



(defn construct-flow
  "Connects the out-mult to the in-chan, pushing it through flow conditions"
  [out-mult {:keys [flow/from flow/to flow/predicate] :as condition}]
  (let [flow-in-chan (a/chan 100)
        flow-out-chan (a/chan 100)
        tap (a/tap out-mult flow-in-chan)
        pfn (resolve-fn predicate)]
    (log/info "Flow condition " from "->" to "via" predicate)
    (a/pipeline 1 flow-out-chan (filter pfn) flow-in-chan)
    (a/mult flow-out-chan)))

(defn setup-flows
  [chans conditions]
  (reduce construct-flow chans conditions))

(defn condition-match? [[in-name out-name :as edge] {:keys [flow/from flow/to] :as condition}]
  (and
   (= from in-name)
   (vector? to)
   (get (set to) out-name)))

(defn connect-edges [conditions ctor-catalog [in-name out-name :as edge]]
  (let [in (get ctor-catalog in-name)
        out (get ctor-catalog out-name)
        flowconds (filter (partial condition-match? edge) conditions)
        _ (log/info "Tapping " in-name "->" out-name)
        flow-out (setup-flows (-> in :edges :out-mult) flowconds)
        tap (a/tap flow-out (-> out :edges :in))]
    (update-in ctor-catalog [out-name :edges :in-tap] conj tap)))

(defn build-pipeline [conditions ctor-catalog workflow]
  (log/info "Building workflow pipeline:" workflow)
  (reduce (partial connect-edges conditions) ctor-catalog workflow))

(defn build-env [cqrs]
  {:lifecycle (reduce (fn [a [k v]] (if (:lifecycle v) (assoc a k (:lifecycle v)) a)) {} (:catmap cqrs))})

(defn start [cqrs]
  (let [env (build-env cqrs)
        ctor-catalog (construct-catalog env (:catalog cqrs))]
    (build-pipeline (:conditions cqrs) ctor-catalog (:workflow cqrs))))

