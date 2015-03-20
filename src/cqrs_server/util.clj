(ns cqrs-server.util
  (:require
   [datomic.api]
   [datomic.function :as df]
   [taoensso.timbre :as log]
   
   [schema.coerce :as coerce]
   [schema.macros :as macros]
   [schema.utils :as utils]
   [schema.core :as s])
  (:import
   [org.joda.time.format ISODateTimeFormat DateTimeFormat]
   [org.joda.time Instant]))

(defmacro defdbfn
  "Define a datomic database function. All calls to datomic api's should be namespaced with datomic.api/ and you cannot use your own namespaces (since the function runs inside datomic)

  This defines a locally namespaced function as well - which is useful for testing.

  Your first parameter needs to always be 'db'.

  You'll need to commit the actual function's meta into your datomic instance by calling (d/transact (meta myfn))"
  [name params & code]
  `(def ~name
     (with-meta
       (fn ~name [~@params]
         ~@code)
       {:tx
        {:db/id (datomic.api/tempid :db.part/user)
         :db/ident ~(keyword name)
         :db/fn (df/construct
                 {:lang "clojure"
                  :params '~params
                  :code '~@code})}})))

(defmacro with-resource
  [binding close-fn & body]
  `(let ~binding
     (try
       (do (log/info "Starting resource: " ~(pr-str body)) ~@body)
       (catch Exception e# (log/info "Exception" (str e#) (log/stacktrace e#)))
       (finally
         (log/info "closing resource: " ~(pr-str body))
         (~close-fn ~(binding 0))))))


(def dateformatters
  [(ISODateTimeFormat/dateTime)
   (.withZoneUTC (DateTimeFormat/forPattern "yyyy-MM-dd"))])

(defn parse-date [df d]
  (if (nil? df)
    nil
    (try
      (Instant. (.parseMillis df d))
      (catch Exception e nil))))

(def string->inst
  (coerce/safe
   #(try
      (let [n (Long/parseLong %)
            ;; Accomodate 'seconds' instead of 'millis'
           n (if (< n 100000000000) (* n 1000) n)]
        (Instant. n))
      (catch Exception e
        (loop [df (first dateformatters)
               r (rest dateformatters)]
          (let [p (parse-date df %)]
            (cond
              (nil? df) %
              (nil? p) (recur (first r) (rest r))
              :else p)))))))

(def Inst Instant)

(defn coercion-matcher [schema]
  (or
   (coerce/string-coercion-matcher schema)
   ({Inst string->inst} schema)))
