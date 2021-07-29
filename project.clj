(defproject evosec/firebird-driver "1.3.0"
  :min-lein-version "2.5.0"

  :dependencies
  [[org.firebirdsql.jdbc/jaybird "4.0.2.java8"]]

  :profiles
  {:provided
   {:dependencies [[metabase-core "1.0.0-SNAPSHOT"]]}

   :uberjar
   {:auto-clean     true
    :aot            :all
    :javac-options  ["-target" "1.8", "-source" "1.8"]
    :target-path    "target/%s"
    :uberjar-name   "firebird.metabase-driver.jar"}})
