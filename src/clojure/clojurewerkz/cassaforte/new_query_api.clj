(ns clojurewerkz.cassaforte.new-query-api
  "Functions for building dynamic CQL queries, in case you feel
   that `cql` namespace is too limiting for you."
  (:import [com.datastax.driver.core.querybuilder QueryBuilder
            Select$Selection Select Select$Where
            BindMarker
            Clause]
           [com.datastax.driver.core TupleType DataType]
           ))

;;
;; Static QB Methods
;;

(defn ?
  ([]
     (QueryBuilder/bindMarker))
  ([name]
     (QueryBuilder/bindMarker name)))

(defn timestamp
  [column-name]
  (QueryBuilder/timestamp column-name))

(defn token
  [& column-names]
  (QueryBuilder/token (into-array column-names)))

(defn function-call
  [name & args]
  (QueryBuilder/fcall name (object-array args)))


(defn asc
  [^String column-name]
  (QueryBuilder/asc (name column-name)))

(defn desc
  [^String column-name]
  (QueryBuilder/desc (name column-name)))

(defn cname
  [^String column-name]
  (QueryBuilder/column column-name))

(defn quote*
  [s]
  (QueryBuilder/quote (name s)))

(let [eq  (fn [^String column ^Object value]
            (QueryBuilder/eq column value))

      in  (fn [^String column values]
            (QueryBuilder/in column values))

      lt  (fn [^String column ^Object value]
            (QueryBuilder/lt column value))

      gt  (fn [^String column ^Object value]
            (QueryBuilder/gt column value))

      lte (fn [^String column ^Object value]
            (QueryBuilder/lte column value))

      gte (fn [^String column ^Object value]
            (QueryBuilder/gte column value))]

  (def ^:private query-type-map
    {:in in
     :=  eq
     =   eq
     :>  gt
     >   gt
     :>= gte
     >=  gte
     :<  lt
     <   lt
     :<= lte
     <=  lte})

  (defprotocol WhereBuilder
    (build-where [construct query-builder]))

  (extend-protocol WhereBuilder
    clojure.lang.IPersistentVector
    (build-where [construct ^Select$Where query-builder]
      (reduce
       (fn [^Select$Where builder [query-type column value]]
         (if-let [eq-type (query-type-map query-type)]
           (.and builder ((query-type-map query-type) (name column) value))
           (throw (IllegalArgumentException. (str query-type " is not a valid Clause")))
           ))
       query-builder
       construct))
    clojure.lang.IPersistentMap
    (build-where [construct ^Select$Where query-builder]
      (reduce
       (fn [^Select$Where builder [column value]]
         (.and builder (eq (name column) value)))
       query-builder
       construct))))

;;
;; Tuples
;;

(def primitive-types
  {:ascii     (DataType/ascii)
   :bigint    (DataType/bigint)
   :blob      (DataType/blob)
   :boolean  (DataType/cboolean)
   :counter   (DataType/counter)
   :decimal   (DataType/decimal)
   :double   (DataType/cdouble)
   :float    (DataType/cfloat)
   :inet      (DataType/inet)
   :int      (DataType/cint)
   :text      (DataType/text)
   :timestamp (DataType/timestamp)
   :uuid      (DataType/uuid)
   :varchar   (DataType/varchar)
   :varint    (DataType/varint)
   :timeuuid  (DataType/timeuuid)})

(defn list-type
  [primitive-type]
  (DataType/list (get primitive-types primitive-type)))

(defn set-type
  [primitive-type]
  (DataType/set (get primitive-types primitive-type)))

(defn map-type
  [key-type value-type]
  (DataType/map (get primitive-types key-type)
                (get primitive-types value-type)))

(defn tuple-of
  [types values]
  (.newValue (TupleType/of (into-array (map #(get primitive-types %) types)))
             (object-array values)))

;;
;; Columns
;;

(defn write-time
  [column]
  (fn writetime-query [query-builder]
    (.writeTime query-builder (name column))))

(defn ttl-column
  [column]
  (fn ttl-query [query-builder]
    (.ttl query-builder (name column))))

(defn distinct*
  [column]
  (fn distinct-query [query-builder]
    (.distinct (.column query-builder column))))

(defn count-all
  []
  [:what (fn count-all-query [query-builder]
           (.countAll query-builder))])

(defn fcall
  [name & args]
  [:what (fn fcall-query [query-builder]
           (.fcall query-builder name (to-array args)))])

(defn all
  []
  (fn all-query [query-builder]
    (.all query-builder)))

(defn as
  [wrapper alias]
  (fn distinct-query [query-builder]
    (.as (wrapper query-builder) alias)))

(defn columns
  [& columns]
  [:what (fn [^Select$Selection query-builder]
           (reduce (fn [^Select$Selection builder column]
                     (if (string? column)
                       (.column builder column)
                       (column builder)))
                   query-builder
                   columns))])


(defn column
  [column & {:keys [as]}]
  [:what (fn column-query [^Select$Selection query-builder]
           (let [c (.column query-builder (name column))]
             (if as
               (.as c as)
               c)))])

(defn where
  [m]
  [:where
   (fn where-query [^Select query-builder]
     (build-where m (.where query-builder)))])

(defn order-by
  [& orderings]
  [:order
   (fn order-by-query [query-builder]
     (.orderBy query-builder (into-array orderings)))])

(defn limit
  [lim]
  [:limit
   (fn order-by-query [^Select  query-builder]
     (.limit query-builder lim))])

(defn allow-filtering
  []
  [:filtering
   (fn order-by-query [^Select  query-builder]
     (.allowFiltering query-builder))])

(defn- from
  [^String table-name]
  [:from (fn from-query [^Select$Selection query-builder]
           (.from query-builder (name table-name))
           )])

(defn- complete-select-query
  [statements]
  (let [query-map (into {} statements)]
    (if (nil? (:what query-map))
      (conj statements
            [:what (all)])
      statements)))

(def ^:private select-order
  {:what      1
   :from      2
   :where     3
   :order     4
   :limit     4
   :filtering 5})

(defn select
  [table-name & statements]
  (->> (conj statements (from (name table-name)))
       (complete-select-query)
       (sort-by #(get select-order (first %)))
       (map second)
       (reduce (fn [builder statement]
                 (println builder statement)
                 (statement builder))
               (QueryBuilder/select)
               )
       (.toString)
       ))

(defn value
  [key value]
  [:values
   (fn value-query [query-builder]
     (.value query-builder (name key) value))])

(defn if-not-exists
  []
  [:if-not-exists
   (fn if-not-exists [query-builder]
     (.ifNotExists query-builder))])

(defn values
  ([m]
     [:values
      (fn order-by-query [query-builder]
        (.values query-builder (into-array (keys m)) (into-array (vals m))))])
  ([key-seq value-seq]
     [:values
      (fn order-by-query [query-builder]
        (.values query-builder (into-array key-seq) (object-array value-seq)))]))

(let [with-values {:timestamp #(QueryBuilder/timestamp %)
                   :ttl       #(QueryBuilder/ttl %)}]
  (defn using
    [m]
    [:using
     (fn using-query [query-builder]
       (doseq [[key value] m]
         (.using query-builder ((get with-values key) value)))
       query-builder)]))

(def ^:private insert-order
  {:values        1
   :using         2
   :if-not-exists 3})

(defn insert
  [table-name & statements]
  (->> statements
       (sort-by #(get select-order (first %)))
       (map second)
       (reduce (fn [builder statement]
                 (println builder statement)
                 (statement builder))
               (QueryBuilder/insertInto (name table-name)))
       (.toString)))

;; Update update(String table)
;; Update update(String keyspace, String table)
;; Update update(TableMetadata table)
;; Delete.Builder delete(String... columns)
;; Delete.Selection delete()
;; Batch batch(RegularStatement... statements)
;; Batch unloggedBatch(RegularStatement... statements)
;; Truncate truncate(String table)
;; Truncate truncate(String keyspace, String table)
;; Truncate truncate(TableMetadata table)
;; String quote(String columnName)
;; Assignment set(String name, Object value)
;; Assignment incr(String name)
;; Assignment incr(String name, long value)
;; Assignment incr(String name, BindMarker value)
;; Assignment decr(String name)
;; Assignment decr(String name, long value)
;; Assignment decr(String name, BindMarker value)
;; Assignment prepend(String name, Object value)
;; Assignment prependAll(String name, List<?> list)
;; Assignment prependAll(String name, BindMarker list)
;; Assignment append(String name, Object value)
;; Assignment appendAll(String name, List<?> list)
;; Assignment appendAll(String name, BindMarker list)
;; Assignment discard(String name, Object value)
;; Assignment discardAll(String name, List<?> list)
;; Assignment discardAll(String name, BindMarker list)
;; Assignment setIdx(String name, int idx, Object value)
;; Assignment add(String name, Object value)
;; Assignment addAll(String name, Set<?> set)
;; Assignment addAll(String name, BindMarker set)
;; Assignment remove(String name, Object value)
;; Assignment removeAll(String name, Set<?> set)
;; Assignment removeAll(String name, BindMarker set)
;; Assignment put(String name, Object key, Object value)
;; Assignment putAll(String name, Map<?, ?> map)
;; Assignment putAll(String name, BindMarker map)
;; Object raw(String str)
;;
;;
