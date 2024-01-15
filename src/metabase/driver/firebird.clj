(ns metabase.driver.firebird
  (:require [clojure
             [set :as set]
             [string :as str]]
            [clojure.java.jdbc :as jdbc]
            [honey.sql :as hsql]
            [honey.sql :as hformat]
            [java-time :as t]
            [metabase.driver :as driver]
            [metabase.driver.common :as driver.common]
            ;; [metabase.driver.sql-jdbc.sync.describe-database :as sql-jdbc.sync.describe-database]
            [metabase.driver.sql-jdbc
             [common :as sql-jdbc.common]
             [connection :as sql-jdbc.conn]
             [sync :as sql-jdbc.sync]]
            [metabase.driver.sql-jdbc.sync.common :as sql-jdbc.sync.common]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.util.honey-sql-2 :as hx]
            [metabase.util.ssh :as ssh])
  (:import [java.sql DatabaseMetaData Time]
           [java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime]
           [java.sql Connection DatabaseMetaData ResultSet]))


(driver/register! :firebird, :parent :sql-jdbc)

(defn- firebird->spec
  "Create a database specification for a FirebirdSQL database."
  [{:keys [host port db jdbc-flags]
    :or   {host "localhost", port 3050, db "", jdbc-flags ""}
    :as   opts}]
  (merge {:classname   "org.firebirdsql.jdbc.FBDriver"
          :subprotocol "firebirdsql"
          :subname     (str "//" host ":" port "/" db jdbc-flags)}
         (dissoc opts :host :port :db :jdbc-flags)))

;; use Honey SQL 2
(defmethod sql.qp/honey-sql-version :firebird
           [_driver]
           2)

;; Obtain connection properties for connection to a Firebird database.
(defmethod sql-jdbc.conn/connection-details->spec :firebird [_ details]
  (-> details
      (update :port (fn [port]
                      (if (string? port)
                        (Integer/parseInt port)
                        port)))
      (set/rename-keys {:dbname :db})
      firebird->spec
      (sql-jdbc.common/handle-additional-options details)))

(defmethod driver/can-connect? :firebird [driver details]
  (let [connection (sql-jdbc.conn/connection-details->spec driver (ssh/include-ssh-tunnel! details))]
    (= 1 (first (vals (first (jdbc/query connection ["SELECT 1 FROM RDB$DATABASE"])))))))

;; Use pattern matching because some parameters can have a length parameter, e.g. VARCHAR(255)
(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
    [[#"INT64"            :type/BigInteger]
     [#"DECIMAL"          :type/Decimal]
     [#"FLOAT"            :type/Float]
     [#"BLOB"             :type/*]
     [#"INTEGER"          :type/Integer]
     [#"NUMERIC"          :type/Decimal]
     [#"DOUBLE"           :type/Float]
     [#"SMALLINT"         :type/Integer]
     [#"CHAR"             :type/Text]
     [#"BIGINT"           :type/BigInteger]
     [#"TIMESTAMP"        :type/DateTime]
     [#"DATE"             :type/Date]
     [#"TIME"             :type/Time]
     [#"BLOB SUB_TYPE 0"  :type/*]
     [#"BLOB SUB_TYPE 1"  :type/Text]
     [#"DOUBLE PRECISION" :type/Float]
     [#"BOOLEAN"          :type/Boolean]]))

;; Map Firebird data types to base types
(defmethod sql-jdbc.sync/database-type->base-type :firebird [_ database-type]
  (database-type->base-type database-type))

;; Use "FIRST" instead of "LIMIT"
(defmethod sql.qp/apply-top-level-clause [:firebird :limit] [_ _ honeysql-form {value :limit}]
    (assoc honeysql-form :modifiers [(format "FIRST %d" value)]))

;; Use "SKIP" instead of "OFFSET"
(defmethod sql.qp/apply-top-level-clause [:firebird :page] [_ _ honeysql-form {{:keys [items page]} :page}]
  (assoc honeysql-form :modifiers [(format "FIRST %d SKIP %d"
                                           items
                                           (* items (dec page)))]))


;; When selecting constants Firebird doesn't check privileges, we have to select all fields
(defn simple-select-probe-query
  [driver schema table]
  {:pre [(string? table)]}
  (let [honeysql {:select [:*]
                  :from   [(sql.qp/->honeysql driver (hx/identifier :table schema table))]
                  :where  [:not= 1 1]}
        honeysql (sql.qp/apply-top-level-clause driver :limit honeysql {:limit 0})]
    (sql.qp/format-honeysql driver honeysql)))

(defn- execute-select-probe-query
  [driver ^Connection conn [sql & params]]
  {:pre [(string? sql)]}
  (with-open [stmt (sql-jdbc.sync.common/prepare-statement driver conn sql params)]
    ;; attempting to execute the SQL statement will throw an Exception if we don't have permissions; otherwise it will
    ;; truthy wheter or not it returns a ResultSet, but we can ignore that since we have enough info to proceed at
    ;; this point.
    (.execute stmt)))

(defmethod sql-jdbc.sync/have-select-privilege? :firebird
  [driver conn table-schema table-name]
  ;; Query completes = we have SELECT privileges
  ;; Query throws some sort of no permissions exception = no SELECT privileges
  (let [sql-args (simple-select-probe-query driver table-schema table-name)]
    (try
      (execute-select-probe-query driver conn sql-args)
      true
      (catch Throwable _
        false))))

(defmethod sql-jdbc.sync/active-tables :firebird [& args]
  (apply sql-jdbc.sync/post-filtered-active-tables args))

;; Convert unix time to a timestamp
(defmethod sql.qp/unix-timestamp->honeysql [:firebird :seconds] [_ _ expr]
  [:DATEADD [:raw "SECOND"] expr (hx/cast :TIMESTAMP (hx/literal "01-01-1970 00:00:00"))])

;; Helpers for Date extraction
;; TODO: This can probably simplified a lot by using String concentation instead of
;; replacing parts of the format recursively

;; Specifies what Substring to replace for a given time unit
(defn- get-unit-placeholder [unit]
  (case unit
    :SECOND :ss
    :MINUTE :mm
    :HOUR   :hh
    :DAY    :DD
    :MONTH  :MM
    :YEAR   :YYYY))

(defn- get-unit-name [unit]
  (case unit
    0 :SECOND
    1 :MINUTE
    2 :HOUR
    3 :DAY
    4 :MONTH
    5 :YEAR))
;; Replace the specified part of the timestamp
(defn- replace-timestamp-part [input unit expr]
  [:replace input (hx/literal (get-unit-placeholder unit)) [:extract unit expr]])

(defn- format-step [expr input step wanted-unit]
  (if (> step wanted-unit)
    (format-step expr (replace-timestamp-part input (get-unit-name step) expr) (- step 1) wanted-unit)
    (replace-timestamp-part input (get-unit-name step) expr)))

(defn- format-timestamp [expr format-template wanted-unit]
  (format-step expr (hx/literal format-template) 5 wanted-unit))

;; Firebird doesn't have a date_trunc function, so use a workaround: First format the timestamp to a
;; string of the wanted resulution, then convert it back to a timestamp
(defn- timestamp-trunc [expr format-str wanted-unit]
  (hx/cast :TIMESTAMP (format-timestamp expr format-str wanted-unit)))

(defn- date-trunc [expr format-str wanted-unit]
  (hx/cast :DATE (format-timestamp expr format-str wanted-unit)))

(defmethod sql.qp/date [:firebird :default]         [_ _ expr] expr)
;; Cast to TIMESTAMP if we need minutes or hours, since expr might be a DATE
(defmethod sql.qp/date [:firebird :minute]          [_ _ expr] (timestamp-trunc (hx/cast :TIMESTAMP expr) "YYYY-MM-DD hh:mm:00" 1))
(defmethod sql.qp/date [:firebird :minute-of-hour]  [_ _ expr] [:extract :MINUTE (hx/cast :TIMESTAMP expr)])
(defmethod sql.qp/date [:firebird :hour]            [_ _ expr] (timestamp-trunc (hx/cast :TIMESTAMP expr) "YYYY-MM-DD hh:00:00" 2))
(defmethod sql.qp/date [:firebird :hour-of-day]     [_ _ expr] [:extract :HOUR (hx/cast :TIMESTAMP expr)])
;; Cast to DATE to get rid of anything smaller than day
(defmethod sql.qp/date [:firebird :day]             [_ _ expr] (hx/cast :DATE expr))
;; Firebird DOW is 0 (Sun) - 6 (Sat); increment this to be consistent with Java, H2, MySQL, and Mongo (1-7)
(defmethod sql.qp/date [:firebird :day-of-week]     [_ _ expr] (hx/+ [:extract :WEEKDAY (hx/cast :DATE expr)] 1))
(defmethod sql.qp/date [:firebird :day-of-month]    [_ _ expr] [:extract :DAY expr])
;; Firebird YEARDAY starts from 0; increment this
(defmethod sql.qp/date [:firebird :day-of-year]     [_ _ expr] (hx/+ [:extract :YEARDAY expr] 1))
;; Cast to DATE because we do not want units smaller than days
;; Use :raw for DAY in dateadd because the keyword :WEEK gets surrounded with quotations
(defmethod sql.qp/date [:firebird :week]            [_ _ expr] [:dateadd [:raw "DAY"] (hx/- 0 [:extract :WEEKDAY (hx/cast :DATE expr)]) (hx/cast :DATE expr)])
(defmethod sql.qp/date [:firebird :week-of-year]    [_ _ expr] [:extract :WEEK expr])
(defmethod sql.qp/date [:firebird :month]           [_ _ expr] (date-trunc expr "YYYY-MM-01" 4))
(defmethod sql.qp/date [:firebird :month-of-year]   [_ _ expr] [:extract :MONTH expr])
;; Use :raw for MONTH in dateadd because the keyword :MONTH gets surrounded with quotations
(defmethod sql.qp/date [:firebird :quarter]         [_ _ expr] [:dateadd [:raw "MONTH"] (hx/* (hx// (hx/- [:extract :MONTH expr] 1) 3) 3) (date-trunc expr "YYYY-01-01" 5)])
(defmethod sql.qp/date [:firebird :quarter-of-year] [_ _ expr] (hx/+ (hx// (hx/- [:extract :MONTH expr] 1) 3) 1))
(defmethod sql.qp/date [:firebird :year]            [_ _ expr] [:extract :YEAR expr])

;; Firebird 2.x doesn't support TRUE/FALSE, replacing them with 1 and 0
(defmethod sql.qp/->honeysql [:firebird Boolean]    [_ bool] (if bool 1 0))

;; Firebird 2.x doesn't support SUBSTRING arugments seperated by commas, but uses FROM and FOR keywords
(defmethod sql.qp/->honeysql [:firebird :substring]
  [driver [_ arg start length]]
  (let [col-name (hformat/to-sql (sql.qp/->honeysql driver arg))]
    (if length
      (reify
        hformat/ToSql
        (to-sql [_]
          (str "substring(" col-name " FROM " start " FOR " length ")")))
      (reify
        hformat/ToSql
        (to-sql [_]
          (str "substring(" col-name " FROM " start ")"))))))

(defmethod sql.qp/add-interval-honeysql-form :firebird [driver hsql-form amount unit]
  (if (= unit :quarter)
    (recur driver hsql-form (hx/* amount 3) :month)
    [:dateadd [:raw (name unit)] amount hsql-form]))

(defmethod sql.qp/current-datetime-honeysql-form :firebird [_]
  (hx/cast :timestamp (hx/literal :now)))

(defmethod driver.common/current-db-time-date-formatters :firebird [_]
  (driver.common/create-db-time-formatters "yyyy-MM-dd HH:mm:ss.SSSS"))

(defmethod driver.common/current-db-time-native-query :firebird [_]
  "SELECT CAST(CAST('Now' AS TIMESTAMP) AS VARCHAR(24)) FROM RDB$DATABASE")

(defmethod driver/current-db-time :firebird [& args]
  (apply driver.common/current-db-time args))

(defmethod sql.qp/->honeysql [:firebird :stddev]
  [driver [_ field]]
  [:stddev_samp (sql.qp/->honeysql driver field)])

;; MEGA HACK based on sqlite driver

(defn- zero-time? [t]
  (= (t/local-time t) (t/local-time 0)))

(defmethod sql.qp/->honeysql [:firebird LocalDate]
  [_ t]
  (hx/cast :DATE (t/format "yyyy-MM-dd" t)))

(defmethod sql.qp/->honeysql [:firebird LocalDateTime]
  [driver t]
  (if (zero-time? t)
    (sql.qp/->honeysql driver (t/local-date t))
    (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))

(defmethod sql.qp/->honeysql [:firebird LocalTime]
  [_ t]
  (hx/cast :TIME (t/format "HH:mm:ss.SSSS" t)))

(defmethod sql.qp/->honeysql [:firebird OffsetDateTime]
  [driver t]
  (if (zero-time? t)
    (sql.qp/->honeysql driver (t/local-date t))
    (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))

(defmethod sql.qp/->honeysql [:firebird OffsetTime]
  [_ t]
  (hx/cast :TIME (t/format "HH:mm:ss.SSSS" t)))

(defmethod sql.qp/->honeysql [:firebird ZonedDateTime]
  [driver t]
  (if (zero-time? t)
    (sql.qp/->honeysql driver (t/local-date t))
    (hx/cast :TIMESTAMP (t/format "yyyy-MM-dd HH:mm:ss.SSSS" t))))

(defmethod driver/supports? [:firebird :basic-aggregations]  [_ _] true)

(defmethod driver/supports? [:firebird :expression-aggregations]  [_ _] true)

(defmethod driver/supports? [:firebird :standard-deviation-aggregations]  [_ _] true)

(defmethod driver/supports? [:firebird :foreign-keys]  [_ _] true)

(defmethod driver/supports? [:firebird :nested-fields]  [_ _] false)

(defmethod driver/supports? [:firebird :set-timezone]  [_ _] false)

(defmethod driver/supports? [:firebird :nested-queries]  [_ _] true)

(defmethod driver/supports? [:firebird :binning]  [_ _] false)

(defmethod driver/supports? [:firebird :case-sensitivity-string-filter-options]  [_ _] false)
