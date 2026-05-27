(ns time-series-module-hard.performance-test-support
  "Performance tests for time-series-module-hard"
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :refer [with-module] :as harness]
   [time-series-module-hard.protocol :as p]))

(defn minute-timestamp
  [minute-bucket]
  (+ (* minute-bucket 60000) 30000))

(defn- filter-events [events event-type]
  (filter #(= event-type (:event-type %)) events))

(defn- count-event-type
  [events event-type]
  (count (filter-events events event-type)))

(defn- generate-spread-data
  [n start-bucket end-bucket]
  (let [range-size (- end-bucket start-bucket)
        step (max 1 (quot range-size n))]
    (into []
      (for [i (range n)
            :let [bucket (+ start-bucket (* i step))]
            :when (< bucket end-bucket)]
        [(inc (mod i 100)) bucket]))))

(defn- capture-query-events
  [f]
  (let [events (atom [])
        result (rtest/with-event-hook
                 (fn [kw data]
                   (swap! events conj (assoc data :event-type kw)))
                 (f))]
    {:result result
     :events @events}))

(defn test-module-performance
  [create-module-fn]
  (with-module [client create-module-fn]
    (let [record! (fn [url millis bucket]
                    (p/record-latency! client url millis (minute-timestamp bucket))
                    (harness/wait-for-processing! client))
          stats (fn [url start end]
                  (p/get-stats-for-range client url start end))]

      (testing "uses granular state for efficient large range queries"
        (let [url "granular.com"
              start-bucket 63
              end-bucket 60543
              data (generate-spread-data 400 start-bucket end-bucket)
              n-points (count data)
              expected-total (reduce + (map first data))]

          (doseq [[latency bucket] data]
            (p/record-latency! client url latency (minute-timestamp bucket)))
          (harness/wait-for-processing! client)

          (testing "returns correct aggregate over large range"
            (let [s (stats url start-bucket end-bucket)]
              (is (= n-points (:cardinality s))
                  (str "expected " n-points " measurements"))
              (is (= expected-total (:total s))
                  (str "expected total=" expected-total))))

          (testing "reads far fewer segments than data points"
            (let [{:keys [events]}
                  (capture-query-events #(stats url start-bucket end-bucket))
                  n-reads (count-event-type events :rocks-read)
                  n-iterators (count-event-type events :rocks-iterator)
                  n-iter-reads (count-event-type events :rocks-iterator-read)
                  topo-events (filter-events events :topology-event)
                  query-events (filter #(= :query (:type %)) topo-events)]
              (is (-> query-events empty? not)
                  "expected query topology")
              (is (< n-reads 10))
              (is (< n-iterators 10))
              (is (< n-iter-reads (quot n-points 4))
                  (str "expected far fewer iter-reads than " n-points
                       " data points, got " n-iter-reads))
              (is (pos? n-iterators)
                  "expected at least one iterator read operation")))))

      (testing "small range query issues minimal reads"
        (record! "small-range.com" 50 100)
        (let [{:keys [events]}
              (capture-query-events #(stats "small-range.com" 100 101))
              n-selects (count-event-type events :local-select)]
          (is (= 1 n-selects)
              (str "single minute range should issue exactly 1 local-select, got " n-selects))))

      (testing "microbatch topology required"
        (let [state (atom #{})]
          (rtest/with-event-hook
            (fn [event-type data]
              (if (= event-type :topology-event)
                (swap! state conj (:type data))))
            (record! "topo-check.com" 42 1000)
            (is (= #{:microbatch} @state)
                "Must use microbatch topology for exactly-once semantics"))))

      (testing "balanced partitioning across tasks"
        (let [num-tasks 4
              user-ids (apply concat
                         (for [i (range 10)]
                           (rtest/gen-hashing-index-keys (str "url-" i) num-tasks)))]
          (let [depot-counts (atom {})]
            (rtest/with-event-hook
              (fn [event-type data]
                (when (= event-type :depot-read)
                  (swap! depot-counts update (:task-id data) (fnil inc 0))))
              (doseq [url user-ids]
                (p/record-latency! client url 10 (minute-timestamp 500)))
              (harness/wait-for-processing! client))
            (let [counts (vals @depot-counts)]
              (is (and (seq counts) (apply = counts))
                  (str "depot reads should be evenly distributed: " @depot-counts)))))))))
