(defproject handif "0.1.0-SNAPSHOT"
  :description "A web application that provides presentation audience members a way to provide immediate feedback to the presenter."
  :url "http://handif.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.datomic/datomic-free "0.8.3627"]
                 [compojure "1.1.3"]
                 [hiccup "1.0.2"]
                 [com.cemerick/friend "0.1.2"]]
  :ring {:handler handif.server/secured-app}
  :datomic {:schemas ["resources/schema" ["handif.dtm"
                                          "initial-data.dtm"]]}
  :profiles {:dev
             {:datomic {:config "resources/free-transactor-template.properties"
                        :db-uri "datomic:free://localhost:4334/handif"}}})
