(ns cqrs-server.simple-test
  (:require
   [clojure.core.async :as a]
   [schema.core :as s]
   [cqrs-server.simple :as simple]
   [cqrs-server.async :as async]
   [cqrs-server.cqrs :as cqrs]

   [clojure.test :refer :all]
   [taoensso.timbre :as log]))

(def catalog
  [{:onyx/name :in
    :onyx/medium :core.async
    :onyx/type :input}

   {:onyx/name :increment
    :onyx/type :function}

   {:onyx/name :mult
    :onyx/type :function
    :multiplier 2
    :onyx/params [:multiplier]
    :onyx/fn :clojure.core/*}

   {:onyx/name :div
    :onyx/type :function
    :divider 2
    :onyx/params [:divider]
    :onyx/fn :clojure.core//}

   {:onyx/name :edge-out
    :onyx/medium :core.async
    :onyx/type :output}
   
   {:onyx/name :edge-in
    :onyx/medium :core.async
    :onyx/type :input}
   
   {:onyx/name :dup
    :onyx/type :function}
   
   {:onyx/name :out
    :onyx/medium :core.async
    :onyx/type :output}])


(def workflow
  [[:in :increment]
   [:increment :dup]
   [:dup :edge-out]
   [:edge-in :mult]
   [:edge-in :div]
   [:mult :out]
   [:div :out]
   ])

(defn div [x y]
  (log/info "div" x y)
  (/ x y))

(defn mult [x y]
  (log/info "mult" x y)
  (* x y))

(def flow-conditions
  [{:flow/from :edge-in
    :flow/to [:mult]
    :flow/predicate :clojure.core/odd?}
   {:flow/from :edge-in
    :flow/to [:div]
    :flow/predicate :clojure.core/even?}])

(defn environment []
  (let [in (a/chan 100)
        out (a/chan 100)
        edge (a/chan 100)]
    {:chans {:in in :out out}
     :lifecycle
     {:in (fn [tmap] {:core.async/chan in})
      :out (fn [tmap] {:core.async/chan out})
      :edge-in (fn [tmap] {:core.async/chan edge})
      :edge-out (fn [tmap] {:core.async/chan edge})
      :increment (fn [tmap] {:onyx.core/fn + :onyx.core/params [5]})
      :dup (fn [tmap] {:onyx.core/fn (fn [x] (repeat 2 x))})}}))

(defn in-out [s x]
  (a/>!! (-> s :env :chans :in) x)
  [(a/<!! (-> s :env :chans :out)) (a/<!! (-> s :env :chans :out))])

(defn setup-test []
  (let [env (environment)
        ctor-catalog (simple/construct-catalog env catalog)]
    {:env env :pipeline (simple/build-pipeline flow-conditions ctor-catalog workflow)}))

(deftest simple []
  (let [s (setup-test)
        out-timeout (-> s :env :chans :out)
        source [1 10 43 12 59 -133000]]
    (a/onto-chan (-> s :env :chans :in) source false)
    (let [output (vec (map (fn [_] (a/<!! out-timeout)) (range 12)))]
      (log/info "Result: " (sort output))
      (assert (= (sort [1/3 1/3 1/24 1/24 30 30 1/32 1/32 34 34 -265990 -265990])
                 (sort output))))))
