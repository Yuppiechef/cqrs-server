(ns cqrs-server.module
  (:require
   [datomic-schema.schema :as ds :refer [schema fields part]]
   [datomic.api :as d]
   [cqrs-server.cqrs :as cqrs]
   [schema.core :as s]))

;; === Sample Module ===

;; Define commands

;; - Create user
;; - Update email address
;; - Disable user
;; - Page view

;; Aggregate Schema

(def db-schema
  [(schema
    user
    (fields
     [name :string :indexed :unique-identity]
     [email :string :indexed :unique-identity]
     [age :long :indexed]
     [status :enum [:active :disabled]]
     [viewcount :long :indexed]))])



;; Command validation

(cqrs/install-commands
  {:user/register {:name s/Str :age s/Int}
   :user/update-email {:uuid s/Uuid :email s/Str}
   :user/disable {:uuid s/Uuid}
   :user/pageview {:uuid s/Uuid :url s/Str :render-time s/Int}})

;; :user/create
(defmethod cqrs/process-command :user/register [{:keys [data] :as c}]
  (if-let [u (d/entity (cqrs/command-db c) [:user/name (:name data)])]
    (cqrs/events c 0 [[:user/register-failed data]])
    (cqrs/events c 1 [[:user/registered data]])))

(defmethod cqrs/aggregate-event :user/registered [{:keys [data] :as e}]
  [{:db/id (d/tempid :db.part/user)
    :base/uuid (d/squuid)
    :user/name (:name data)
    :user/age (:age data)
    :user/status :user.status/active}])

;; :user/update-email
(defmethod cqrs/process-command :user/update-email [{:keys [data] :as c}]
  (if-let [u (d/entity (cqrs/command-db c) [:base/uuid (:uuid data)])]
    (cqrs/events c 0 [[:user/email-updated data]])
    (cqrs/events c 1 [[:user/email-update-failed data]])))

(defmethod cqrs/aggregate-event :user/email-updated [{:keys [data] :as e}]
  [{:db/id [:base/uuid (:uuid data)] :user/email (:email data)}])


;; :user/disable
(defmethod cqrs/process-command :user/disable [{:keys [data] :as c}]
  (if-let [u (d/entity (cqrs/command-db c) [:base/uuid (:uuid data)])]
    (cqrs/events c 0 [[:user/disabled data]])
    (cqrs/events c 1 [[:user/disable-failed data]])))

(defmethod cqrs/aggregate-event :user/disabled [{:keys [data] :as e}]
  [{:db/id [:base/uuid (:uuid data)] :user/status :user.status/disabled}])


;; :user/pageview
(defmethod cqrs/process-command :user/pageview [{:keys [data] :as c}]
  (cqrs/events c 0 [[:user/pageviewed data]]))

(defmethod cqrs/aggregate-event :user/pageviewed [{:keys [data] :as e}]
  [[:add [:base/uuid (:uuid data)] :user/viewcount 1]])
