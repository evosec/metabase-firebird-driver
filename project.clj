(defproject evosec/firebird-driver "1.0.0"
  :min-lein-version "2.5.0"

  :dependencies
  [[org.firebirdsql.jdbc/jaybird-jdk18 "3.0.5"]]

  :profiles
  {:provided
   {:dependencies
    [[org.clojure/clojure "1.10.0"]
     [metabase-core "1.0.0-SNAPSHOT"]]}

   :uberjar
   {:auto-cleam     true
    :aot            :all
    :omit-source    true
    :javac-options  ["-target" "1.8", "-source" "1.8"]
    :target-path    "target/%s"
    :uberjar-name   "firebird.metabase-driver.jar"}})
