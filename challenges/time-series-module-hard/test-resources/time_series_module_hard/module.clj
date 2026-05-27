(ns time-series-module-hard.module
  "Reference implementation for time-series-module-hard challenge."
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.ops :as ops]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [time-series-module-hard.protocol]))

(defrecord RenderLatency [url render-millis timestamp-millis])

(defrecord WindowStats [cardinality total min-latency-millis max-latency-millis])

(defn empty-window-stats
  "Creates an empty WindowStats with default values."
  []
  (->WindowStats 0 0 nil nil))

(defn make-single-stat
  "Creates WindowStats from a single measurement."
  [render-millis]
  (->WindowStats 1 render-millis render-millis render-millis))

;;; Combiner aggregator for WindowStats

(defn merge-window-stats
  "Merges two WindowStats, handling nil inputs."
  [ws1 ws2]
  (cond
    (nil? ws1) ws2
    (nil? ws2) ws1
    :else
    (->WindowStats
     (+ (:cardinality ws1) (:cardinality ws2))
     (+ (:total ws1) (:total ws2))
     (cond
       (nil? (:min-latency-millis ws1)) (:min-latency-millis ws2)
       (nil? (:min-latency-millis ws2)) (:min-latency-millis ws1)
       :else (min (:min-latency-millis ws1) (:min-latency-millis ws2)))
     (cond
       (nil? (:max-latency-millis ws1)) (:max-latency-millis ws2)
       (nil? (:max-latency-millis ws2)) (:max-latency-millis ws1)
       :else (max (:max-latency-millis ws1) (:max-latency-millis ws2))))))

(def +combine-measurements
  "Combiner aggregator that merges WindowStats objects."
  (combiner merge-window-stats :init-fn empty-window-stats))

;;; Rama operation for emitting all granularities

(deframaop emit-index-granularities [*timestamp-millis]
  (long (/ *timestamp-millis 60000) :> *minute-bucket)
  (long (/ *minute-bucket 60) :> *hour-bucket)
  (long (/ *hour-bucket 24) :> *day-bucket)
  (long (/ *day-bucket 30) :> *thirty-day-bucket)
  (:> :m *minute-bucket)
  (:> :h *hour-bucket)
  (:> :d *day-bucket)
  (:> :td *thirty-day-bucket))

;;; Granularity constants for query optimization

(def next-granularity
  "Maps each granularity to its next coarser level."
  {:m :h, :h :d, :d :td})

(def next-granularity-divisor
  "Number of finer buckets per coarser bucket."
  {:m 60, :h 24, :d 30})

(defn query-granularities
  "Computes the minimal set of granularity/bucket ranges needed to query a minute range.
   Returns a vector of [granularity start-bucket end-bucket] tuples."
  [granularity start-bucket end-bucket]
  (let [next-gran (next-granularity granularity)]
    (if (nil? next-gran)
      [[granularity start-bucket end-bucket]]
      (let [divisor (next-granularity-divisor granularity)
            next-start-bucket (cond-> (long (/ start-bucket divisor))
                                (not= 0 (mod start-bucket divisor)) inc)
            next-end-bucket (long (/ end-bucket divisor))
            next-aligned-start (* next-start-bucket divisor)
            next-aligned-end (* next-end-bucket divisor)
            more (when (> next-end-bucket next-start-bucket)
                   (query-granularities next-gran next-start-bucket next-end-bucket))]
        (concat more
                (if (>= next-aligned-start next-aligned-end)
                  [[granularity start-bucket end-bucket]]
                  (cond-> []
                    (> next-aligned-start start-bucket)
                    (conj [granularity start-bucket next-aligned-start])
                    (> end-bucket next-aligned-end)
                    (conj [granularity next-aligned-end end-bucket]))))))))

;;; Module definition

(defmodule TimeSeriesHard [setup topologies]
  (declare-depot setup *render-latency-depot (hash-by :url))

  (let [mb (microbatch-topology topologies "timeseries")]

    ;; Window stats: url -> granularity -> bucket -> WindowStats
    (declare-pstate mb $$window-stats
                    {String
                     {clojure.lang.Keyword
                      (map-schema Long WindowStats {:subindex? true})}})

    (<<sources mb
               (source> *render-latency-depot :> %microbatch)
               (%microbatch :> {:keys [*url *render-millis *timestamp-millis]})
               (make-single-stat *render-millis :> *single-stat)
               (emit-index-granularities *timestamp-millis :> *granularity *bucket)
               (+compound $$window-stats
                          {*url {*granularity {*bucket (+combine-measurements *single-stat)}}}))

    ;; Query topology: efficiently fetch aggregated stats for a minute range
    (<<query-topology topologies "get-stats-for-range"
                      [*url *start-bucket *end-bucket :> *stats]
                      (|hash *url)
                      (ops/explode (query-granularities :m *start-bucket *end-bucket)
                                   :> [*granularity *g-start *g-end])
                      (local-select> [(keypath *url *granularity)
                                      (sorted-map-range *g-start *g-end)
                                      MAP-VALS]
                                     $$window-stats
                                     :> *bucket-stat)
                      (|origin)
                      (+combine-measurements *bucket-stat :> *stats))))


(defn make-client
  "Creates a protocol client for the time series hard module."
  [ipc]
  (let [module-name  (get-module-name TimeSeriesHard)
        depot        (foreign-depot ipc module-name "*render-latency-depot")
        get-stats-q  (foreign-query ipc module-name "get-stats-for-range")
        cnt          (atom 0)]
    (reify time-series-module-hard.protocol/TimeSeriesModuleHard
      (record-latency! [_ url render-millis timestamp-millis]
        (swap! cnt inc)
        (foreign-append! depot (->RenderLatency url render-millis timestamp-millis)))
      (get-stats-for-range [_ url start-bucket end-bucket]
        (foreign-invoke-query get-stats-q url start-bucket end-bucket))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc module-name "timeseries" @cnt)))))

(defn create-module
  "Returns the module descriptor for the time-series-module-hard challenge."
  []
  {:module      TimeSeriesHard
   :wrap-client make-client})
