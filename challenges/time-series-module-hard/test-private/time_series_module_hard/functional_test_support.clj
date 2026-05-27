(ns time-series-module-hard.functional-test-support
  "Functional private tests for time-series-module-hard (moved from public tests)."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :refer [with-module] :as harness]
   [time-series-module-hard.protocol :as p]))

(defn minute-timestamp
  "Returns a timestamp in the middle of the given minute bucket."
  [minute-bucket]
  (+ (* minute-bucket 60000) 30000))

(defn test-module-functional
  [create-module-fn]
  (with-module [client create-module-fn]
    (let [record! (fn [url millis bucket]
                    (p/record-latency! client url millis (minute-timestamp bucket))
                    (harness/wait-for-processing! client))
          stats (fn [url start end]
                  (p/get-stats-for-range client url start end))]

      (testing "get-stats-for-range"
        (testing "with no data recorded"
          (testing "returns zero cardinality for unknown URL"
            (let [s (stats "none.com" 0 100)]
              (is (= 0 (:cardinality s)))
              (is (= 0 (:total s)))))

          (testing "returns zero cardinality for empty range"
            (let [s (stats "none.com" 5 5)]
              (is (= 0 (:cardinality s))))))

        (testing "with a single measurement"
          (record! "a.com" 42 5)

          (testing "returns stats for the containing bucket"
            (let [s (stats "a.com" 5 6)]
              (is (= 1 (:cardinality s)))
              (is (= 42 (:total s)))
              (is (= 42 (:min-latency-millis s)))
              (is (= 42 (:max-latency-millis s)))))

          (testing "returns zero for range not containing the bucket"
            (let [s (stats "a.com" 6 10)]
              (is (= 0 (:cardinality s)))))

          (testing "respects exclusive end boundary"
            (let [s (stats "a.com" 0 5)]
              (is (= 0 (:cardinality s))))))

        (testing "with multiple measurements in one bucket"
          (record! "b.com" 10 3)
          (record! "b.com" 20 3)

          (testing "aggregates count and sum"
            (let [s (stats "b.com" 3 4)]
              (is (= 2 (:cardinality s)))
              (is (= 30 (:total s)))))

          (testing "tracks min and max"
            (let [s (stats "b.com" 3 4)]
              (is (= 10 (:min-latency-millis s)))
              (is (= 20 (:max-latency-millis s))))))

        (testing "with measurements across multiple buckets"
          (record! "c.com" 10 3)
          (record! "c.com" 20 3)
          (record! "c.com" 15 10)
          (record! "c.com" 18 10)
          (record! "c.com" 33 10)

          (testing "returns stats for a single bucket"
            (let [s (stats "c.com" 3 4)]
              (is (= 2 (:cardinality s)))
              (is (= 30 (:total s)))
              (is (= 10 (:min-latency-millis s)))
              (is (= 20 (:max-latency-millis s)))))

          (testing "aggregates across buckets in range"
            (let [s (stats "c.com" 3 11)]
              (is (= 5 (:cardinality s)))
              (is (= 96 (:total s)))
              (is (= 10 (:min-latency-millis s)))
              (is (= 33 (:max-latency-millis s)))))

          (testing "excludes buckets outside the range"
            (let [s (stats "c.com" 4 10)]
              (is (= 0 (:cardinality s)))))

          (testing "returns zero for range with no data"
            (let [s (stats "c.com" 100 200)]
              (is (= 0 (:cardinality s))))))

        (testing "with measurements spanning large time ranges"
          (record! "d.com" 10 3)
          (record! "d.com" 20 3)
          (record! "d.com" 15 10)
          (record! "d.com" 20 65)
          (record! "d.com" 30 65)
          (record! "d.com" 100 1440)
          (record! "d.com" 100 1448)
          (record! "d.com" 50 3002)

          (let [s (stats "d.com" 0 4320)]
            (is (= 8 (:cardinality s)))
            (is (= 345 (:total s)))
            (is (= 10 (:min-latency-millis s)))
            (is (= 100 (:max-latency-millis s))))

          (let [s (stats "d.com" 0 60)]
            (is (= 3 (:cardinality s)))
            (is (= 45 (:total s))))

          (let [s (stats "d.com" 60 120)]
            (is (= 2 (:cardinality s)))
            (is (= 50 (:total s))))

          (let [s (stats "d.com" 1440 1500)]
            (is (= 2 (:cardinality s)))
            (is (= 200 (:total s)))))

        (testing "with multiple URLs"
          (record! "x.com" 50 3)
          (record! "x.com" 60 3)
          (record! "y.com" 5 3)

          (testing "tracks each URL independently"
            (let [x-stats (stats "x.com" 3 4)
                  y-stats (stats "y.com" 3 4)]
              (is (= 2 (:cardinality x-stats)))
              (is (= 110 (:total x-stats)))
              (is (= 50 (:min-latency-millis x-stats)))
              (is (= 60 (:max-latency-millis x-stats)))
              (is (= 1 (:cardinality y-stats)))
              (is (= 5 (:total y-stats))))))
        ))))
