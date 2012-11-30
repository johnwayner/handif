(ns handif.page
  (:require [hiccup.page :as h]
            [hiccup.form :as f]
            [hiccup.util :as hu]
            [datomic.api :as d]
            [cemerick.friend :as friend]))

(defn header [authenticated?]
  [:div.navbar.navbar-inverse.navbar-fixed-top
   [:div.navbar-inner
    [:div.container-fluid
     [:a.btn.btn-navbar.collapsed {:data-toggle "collapse"
                                   :data-target ".nav-collapse"}
      (for [x (range 3)]
        [:span.icon-bar])]
     [:a.brand {:href "/"} "raise your Hand If"]
     [:div.nav-collapse.collapse
      [:ul.nav
       [:li.active [:a {:href "/find-presentation"} "Find"]]
       (if authenticated?
         (h/html5
          [:li.active [:a {:href "/create-presentation"} "Create"]]
          [:li.active [:a {:href "/my-presentations"} "My Presentations"]]
          [:li.active [:a {:href "/logout"} "Logout"]])
         (h/html5 [:li.active [:a {:href "/signup"} "Sign Up"]]
                  [:li.active [:a {:href "/login"} "Login"]]))
       [:li.active [:a {:href "/about"} "About"]]]]]]])

(defn footer []
  [:div.row-fluid
   [:div.span1]
   [:div.span8
    ;;nothing for now
    ]])

(defn template [authenticated? body-fn args]
  (h/html5
   [:head
    [:title "Hand If"]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1.0"}]
    (h/include-css "/css/bootstrap.min.css")
    (h/include-css "/css/bootstrap-responsive.css")
    (h/include-js "/js/jquery-1.8.3.min.js")
    (h/include-js "/js/bootstrap.js")
    "<style type=\"text/css\" >
  body {
    padding-top: 60px;
  }
  @media (max-width: 979px) {
    body {
      padding-top: 0;
    }
  }
</style>"
    ]
   [:body 
    (header authenticated?)
    [:div.container
     (apply body-fn args)
     [:br]]
    (footer)]))

(defn not-found []
  [:h2 "Not Found."])

(defn index [recent-pres]
  (h/html5 [:div.row-fluid
            [:div.span12
             [:span "HandIf provides a way to collaborate with your audience members in real time.  Create polls and avoid the dreaded \"Raise your hand if...\" questions in your presentations.  But more importantly, get instant feedback about how your message is being received so you can react on the fly." ]]]
           [:br]
           [:div.row-fluid
            [:span "Recent Presentations:"]
            [:ul
             (for [[pid name] recent-pres]
               [:li [:a {:href (str "/presentation/" pid)}
                     name]])]]))

(defn form [action btn fields]
  [:div.row
   [:form.form-horizontal {:action action :method "post"}
    (for [[name label type prefill & extra] fields]
      [:div.control-group
       [:label.control-label {:for name} label]
       [:div.controls 
        [:input {:type type
                 :id name
                 :name name
                 :placeholder label
                 :value prefill
                 :autofocus "autofocus"
                 :style "margin-right: 5px;"}]
        extra]])
    [:div.control-group
     [:div.controls
      [:button.btn {:type "submit"} btn]]]]])

(defn create-presenter-form [username]
  [:div
   [:div.row
    [:h2 "Create a presenter account."]
    [:span "This account will allow you to create presentations.  If you just want to participate in someone else's presentation, you don't need an account."]]
   [:br]
   (form "/signup" "Sign Up"
         [["username" "Username" "text" username]
          ["password" "Password" "password" nil]
          ["confirm-password" "Confirm Password" "password" nil]])])

(defn create-presenter-success [username]
  [:div
   [:div.row
    [:span (str "New presenter created: " username)]]
   [:div.row
    [:button.btn.btn-primary "Create your first presentation!"]]])

(defn login [username]
  [:div
   [:div.row
    [:h2 "Log into a presenter account."]]
   [:br]
   (form "/login" "Login"
         [["username" "Username" "text" username]
          ["password" "Password" "password" nil]])])

(defn create-presentation
  ([] (create-presentation nil nil))
  ([name location]
     [:div
      [:div.row
       [:h2 "Create a new presentation."]
       [:br]
       (form "/create-presentation" "Create"
             [["name" "Name" "text" name]
              ["location" "Location" "text" location]])]]))

(defn my-presentations [presentations]
  [:div
   [:div.row
    [:h2 "Your Presentations"]
    [:br]
    [:ul
     (for [[name id instant] (sort (fn [[_ _ i1] [_ _ i2]] (.compareTo i2 i1)) presentations)]
       [:li [:a {:href (str "/presentation/" id)}
             (hu/escape-html (str name " - " instant))]])]]])

(defn get-feedback-type-ord [foption]
  ({:ongoing-feedback-type/positive 1
    :ongoing-feedback-type/neutral 2
    :ongoing-feedback-type/negative 3}
   (:ongoing-feedback/type foption)))

(defn show-presentation [presentation owner? feedbacks]
  [:div
   [:div.row-fluid
    [:h2 (:prez/name presentation)]
    [:h3 (:common/location presentation)]
    (if owner?
      (h/html5
       [:div.row-fluid
        [:span "This is yours!"]]
       (for [[name time member-id] (reverse (sort-by second feedbacks))]
         [:div.row-fluid
          [:div.span1]
          [:div.span1
           [:span.feedback (hu/escape-html name)]]
          [:div.span3
           [:span.feedback-time (hu/escape-html time)]]
          [:div.span2
           [:span.feedback-memberid (hu/escape-html member-id)]]]))
      (h/html5
       [:div.row-fluid
        [:span "Give feedback."]]
       [:div.row-fluid
        [:div.span2]
        [:div.span4
         [:form {:action (str "/submit-feedback/" (:db/id presentation))
                 :method :post}
          (for [option (sort-by get-feedback-type-ord 
                                (map #(d/entity (d/entity-db presentation) %)
                                     (:prez/ongoing-feedback-option presentation)))]
            [:button {:class (str "btn btn-large btn-block "
                                  (case (:ongoing-feedback/type option)
                                    :ongoing-feedback-type/positive "btn-success"
                                    :ongoing-feedback-type/negative "btn-danger"
                                    :ongoing-feedback-type/neutral ""))
                      :type "submit"
                      :name "feedback-id"
                      :value (:db/id option)}
             (str (:common/name option))])]]]))]])

(defn find-presentation [results]
  [:div
   [:div.row
    [:h2 "Find Presentation"]]
   (form "/find-presentation" "Find"
         [["query" "Name" "text" nil
           [:span "You can use "
            [:a {:href "http://lucene.apache.org/core/3_6_1/queryparsersyntax.html"}
             "Query Parser Syntax."]]]])
   (if results
     (h/html5
      [:div.row [:h3 "Results"]]
      [:div.row
       (if (= 0 (count results))
         [:span "Nothing found."]
         [:ul
          (for [[id name] results]
            [:li [:a {:href (str "/presentation/" id)}
                  (hu/escape-html name)]])])]))])