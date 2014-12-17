(defproject slidescli "0.0.1-SNAPSHOT"
  :description "Control slid.es live presentations from console"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clj-http "1.0.1"]
                 [hickory "0.5.4"]
                 [org.clojure/data.json "0.2.5"]]
  :main slidescli.core
  :aot :all)
