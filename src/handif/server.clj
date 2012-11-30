(ns handif.server
  (:require [handif.page :as page]
            [ring.util.response :as resp]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [cemerick.friend :as friend]
            (cemerick.friend [workflows :as workflows]
                             [credentials :as creds])
            [datomic.api :as d]
            [clojure.set :as set])
  (:use [compojure.core :only [defroutes GET POST ANY]]))

(def db-conn-delay (delay (d/connect "datomic:free://localhost:4334/handif")))

(defn page [body-fn & args]
  (page/template (not (nil? (friend/current-authentication)))
                 body-fn
                 args))

(defn create-presenter [username password confirm-password]
  (let [username (.trim username)]
    (if (or (empty? username)
            (not= password confirm-password)
            (instance? java.lang.Error
              @(d/transact @db-conn-delay [{:db/id (d/tempid :db.part/user)
                                            :user/username username
                                            :user/password-hash (creds/hash-bcrypt password)
                                            :user/roles (name :presenter)}])))
      ;;TODO: add some error message
      (page page/create-presenter-form username)
      ;;TODO: auto login the new user
      (page page/login username))))


(defn create-presentation [current-user name location]
  (let [
        result (d/transact @db-conn-delay
                           (let [temp-id (d/tempid :db.part/user)]
                             (concat
                              [{:db/id temp-id
                                :prez/name name
                                :common/location location
                                :prez/presenter current-user}]
                              (for [f [:ongoing-feedback/enjoying
                                       :ongoing-feedback/ok
                                       :ongoing-feedback/confused
                                       :ongoing-feedback/bored]]
                                [:db/add temp-id 
                                 :prez/ongoing-feedback-option f]))))]
    (if (instance? java.lang.Error
                  @result)
      (page page/create-presentation name location)
      (resp/redirect "/my-presentations"))))


(defroutes app-routes
  (GET "/" req (page
                page/index
                (sort-by #(nth % 2)
                          (d/q '[:find ?p ?name ?instant :where
                                 [?p :prez/name ?name ?tx]
                                 [?tx :db/txInstant ?instant]]
                               (:db req)))))
  (GET "/login" [username] (page page/login username))
  (friend/logout (ANY "/logout" request (resp/redirect "/")))
  (GET "/signup" [username] (page page/create-presenter-form username))
  (POST "/signup" [username password confirm-password]
        (create-presenter username password confirm-password))
  (GET "/create-presentation" [name location]
       (friend/authorize #{:presenter} (page page/create-presentation name location)))
  (POST "/create-presentation" [name location]
        (friend/authorize #{:presenter} (create-presentation
                                         (:id (friend/current-authentication))
                                         name location)))
  (GET "/my-presentations" req
       (friend/authorize #{:presenter} (page page/my-presentations
                                        (d/q '[:find ?name ?p ?instant
                                               :in $ ?uid
                                               :where
                                               [?p :prez/presenter ?uid ?tx]
                                               [?p :prez/name ?name]
                                               [?tx :db/txInstant ?instant]]
                                             (:db req)
                                             (:id (friend/current-authentication))))))
  (GET ["/presentation/:id" :id #"[0-9]+"] [id :as req]
       (let [presentation (d/touch (d/entity (:db req) (Long/valueOf id)))
             owner? (= (:id (friend/current-authentication))
                       (get-in presentation [:prez/presenter :db/id]))]
         (page page/show-presentation
               presentation
               owner?
               (if owner?
                 (d/q '[:find ?fb-name ?time ?mid
                        :in $ ?pres
                        :where
                        [?fb :common/presentation ?pres ?tx]
                        [?fb :feedback/member ?m]
                        [?m  :member/uuid ?mid]
                        [?fb :feedback/on-going ?og]
                        [?og :common/name ?fb-name]
                        [?tx :db/txInstant ?time]]
                      (:db req)
                      (:db/id presentation))
                 nil))))
  (GET "/find-presentation" [] (page page/find-presentation nil))
  (POST "/find-presentation" [query :as req]
        (page page/find-presentation
              (if (> (count query) 0)
                (try
                  (reverse (sort-by #(nth % 2)
                                    (d/q '[:find ?p ?name ?rank
                                           :in $ ?query
                                           :where
                                           [(fulltext $ :prez/name ?query) [[?p ?name _ ?rank]]]]
                                         (:db req)
                                         query)))
                  (catch java.lang.Exception e [])))))
  (GET ["/submit-feedback/:presentation-id" :presentation-id #"[0-9]+"]
        [presentation-id]
        (resp/redirect (str "/presentation/" presentation-id)))
  (POST ["/submit-feedback/:presentation-id" :presentation-id #"[0-9]+"]
        [presentation-id feedback-id :as req]
        (try
          @(d/transact @db-conn-delay
                       [{:db/id (d/tempid :db.part/user)
                         :common/presentation (Long/valueOf presentation-id)
                         :feedback/time (java.util.Date.)
                         :feedback/member (get-in req [:session :member-id])
                         :feedback/on-going (Long/valueOf feedback-id)}])
          (resp/redirect (str "/presentation/" presentation-id))
          (catch java.lang.Error e e)))
  (route/resources "/")
  (route/not-found (page page/not-found)))

(def site-handler
  (handler/site app-routes))

(defn load-user [username]
  (let [conn @db-conn-delay
        db (d/db conn)
        user-ent (d/entity db (ffirst (d/q '[:find ?u :in $ ?username :where
                                             [?u :user/username ?username]]
                                           db
                                           username)))]
    
    ;;perpare user map for usage by creds/bcrypt-credential-fn
    (when (not (nil? user-ent))
      (update-in (set/rename-keys (merge {:id (:db/id user-ent)}
                                         (d/touch user-ent))
                                  {:user/username :username
                                   :user/roles :roles
                                   :user/password-hash :password})
                 [:roles]
                 (partial map keyword)))))

(defn wrap-with-db [handler]
  (fn [req]
    (handler (assoc req
               :db (d/db @db-conn-delay)))))

(defn wrap-with-member-id [handler]
  (fn [req]
    (if (nil? (get-in req [:session :member-id]))
      (let [uuid (java.util.UUID/randomUUID)
            temp-id (d/tempid :db.part/user)
            result @(d/transact @db-conn-delay
                                [{:db/id temp-id
                                  :member/uuid uuid
                                  :member/ip (:remote-addr req)}])
            add-id-fn #(update-in % [:session]
                                  assoc
                                  :member-id
                                  (d/resolve-tempid (:db-after result)
                                                    (:tempids result)
                                                    temp-id))]
        (add-id-fn (handler (add-id-fn req))))
      (handler req))))

(def secured-app
  (-> app-routes
      (friend/authenticate {:credential-fn (partial creds/bcrypt-credential-fn load-user)
                            :default-landing-uri "/my-presentations"
                            :workflows [(workflows/interactive-form)]})
      wrap-with-db
      wrap-with-member-id
      handler/site))

