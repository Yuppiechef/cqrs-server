(ns cqrs-server.onyx
  (:require
   [onyx.api]
   [taoensso.timbre :as log]
   [cqrs-server.util :as util]
   [onyx.peer.task-lifecycle-extensions :as l-ext]))

(defn lifecycle-resource [ident resource-fn]
  (defmethod l-ext/inject-lifecycle-resources ident [_ pipeline-data]
    (resource-fn (:onyx.core/task-map pipeline-data))))

(defn start [setup-map env-config peer-config]
  (let [penv (assoc env-config :onyx/id (:onyxid setup-map))
        pconfig (assoc peer-config :onyx/id (:onyxid setup-map))
        kill-latch (promise)
        killed-latch (promise)
        started-latch (promise)]
    (log/info "Starting onyx")
    (future
      (util/with-resource [env (onyx.api/start-env penv)] onyx.api/shutdown-env
        (log/info "Started onyx")
        (util/with-resource [peers (onyx.api/start-peers 10 pconfig)] #(doseq [p %] (onyx.api/shutdown-peer p))
          (log/info "Started peers")
          (util/with-resource
            [job (onyx.api/submit-job
                  pconfig {:catalog (:catalog setup-map)
                           :workflow (:workflow setup-map)
                           :task-scheduler :onyx.task-scheduler/round-robin})]
            (partial onyx.api/kill-job penv)
            (doseq [[ident c] (:catmap setup-map)]
              (lifecycle-resource ident (:lifecycle c)))
            (do
              (log/info "Submitted jobs")
              (deliver started-latch true)
              @kill-latch))))
      (deliver killed-latch true))
    {:shutdown (fn [] (deliver kill-latch true) @killed-latch)
     :started-latch started-latch}))





