(ns pallet.task.tomcat
  "Deploy to tomcat."
  (:require
   [pallet.core :as core]
   [pallet.action.package :as package]
   [pallet.action.service :as service]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.java :as java]
   [pallet.crate.tomcat :as tomcat]
   [pallet.phase :as phase]
   [clojure.contrib.logging :as logging]))

(defn war-file-name
  [project]
  (format "%s-%s.war" (:name project) (:version project)))

(defn find-war
  [project]
  (some
   #(let [f (% project)] (and (.exists (java.io.File. f)) f))
   [war-file-name]))


(defn bootstrap
  "Common Bootstrap"
  [request]
  (->
   request
   (automated-admin-user/automated-admin-user)
   (package/package-manager :update)))

(defn tomcat-install
  "Tomcat server configuration"
  [request]
  (-> request
      (java/java :openjdk)
      (tomcat/tomcat)))

(defn tomcat-deploy
  "Tomcat deploy as ROOT application"
  [request path]
  (-> request
      (tomcat/deploy "ROOT" :local-file path :clear-existing true)))

(defn tomcat
  "Deploy war file to tomcat.
   This will create a node based on your cloud credentials.  By default the
   node will have a webapp tag."
  [request & args]
  (let [war (find-war (:project request))
        options (-> request :project :pallet)
        command (first args)
        command (and command (#{"deploy" "destroy" "restart"} (name command)))
        node-count (:count options 1)
        node (core/make-node
              (:group-name options "webapp")
              (:template options {:inbound-ports [8080 22]})
              :bootstrap (phase/phase-fn (bootstrap))
              :configure (phase/phase-fn
                          (tomcat-install)
                          (tomcat-deploy war))
              :deploy (phase/phase-fn
                       (tomcat-deploy war))
              :restart (phase/phase-fn
                        (service/service "tomcat6" :action :restart)))]
    (if war
      (core/converge
       {node (if (= command "destroy") 0 node-count)}
       :compute (:compute request)
       :phase (or (and command (#{:deploy :restart} (keyword (name command))))
                  :configure))
      (println "Unable to find war file."))))
