(ns bank-transfer-module.performance-test-support
  "Performance tests for bank-transfer-module"
  (:require
   [bank-transfer-module.protocol :as p]
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]))

(defn capture-rocks-ops [f]
  (let [state (atom {})]
    (rtest/with-event-hook
      (fn [event-type data]
        (case event-type
          :rocks-read (swap! state update :rocks-read (fnil inc 0))
          :rocks-iterator (swap! state update :rocks-iterator (fnil inc 0))
          :rocks-iterator-read (swap! state update :rocks-iterator-read (fnil inc 0))
          :rocks-commit (swap! state update :rocks-writes (fnil #(+ % (:write-batch-count data)) 0))
          nil
          ))
      (f)
      @state)))

(defn- check-balance! [client num-tasks]
  (let [state (atom {:topo-counts {} :depot-counts {} :partitioner? false})
        user-ids (apply concat
                   (for [i (range 10)]
                     (rtest/gen-hashing-index-keys (str i) num-tasks)))]
    (rtest/with-event-hook
      (fn [event-type data]
        (case event-type
          :depot-read     (swap! state update-in [:depot-counts (:task-id data)] (fnil inc 0))
          :partitioner    (swap! state assoc :partitioner? true)
          nil))
      (doseq [id user-ids]
        (p/deposit! client id 1))
      (harness/wait-for-processing! client))
    (let [{:keys [topo-counts depot-counts partitioner?]} @state]
      (testing "balanced partitioning"
        (testing "depot reads distributed evenly across tasks"
          (let [counts (vals depot-counts)]
            (is (and (-> counts empty? not) (apply = counts))
                (str "depot-read counts should be equal across tasks (note that this event only happens microbatch topology): " depot-counts))))
        (testing "no partitioner calls"
          (is (not partitioner?)
              "expected no :partitioner events"))))))

(defn test-module-performance
  [create-module-fn]
  (let [result (create-module-fn)
        module (:module result)
        wrap-client (:wrap-client result)
        tasks (rand-nth [2 4])
        threads (+ 2 (rand-int (dec tasks)))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads threads})
      (let [client (wrap-client ipc)
            sync! (fn [] (harness/wait-for-processing! client))]
        (let [state (atom #{})]
          (rtest/with-event-hook
            (fn [event-type data]
              (if (= event-type :topology-event)
                (swap! state conj (:type data))))
            (p/deposit! client "alice" 1000)
            (p/deposit! client "bob" 1000)
            (sync!)
            (dotimes [i 49]
              (p/transfer! client (str "t" i) "alice" "bob" 1))
            (sync!)
            (is (= #{:microbatch} @state) "Did not use microbatch topology for ETL, which is necessary for exactly-once semantics needed by challenge")))

        (let [info (capture-rocks-ops
                     (fn []
                       (p/deposit! client "charlie" 1000)
                       (sync!)
                       ))]
          (is (= info {:rocks-read 1 :rocks-writes 1}) "Incorrect number of RocksDB operations for deposit"))

         (let [info (capture-rocks-ops
                      (fn []
                        (p/transfer! client "tt0" "alice" "bob" 10)
                        (sync!)
                        ))]
           (is (<= (:rocks-read info) 8))
           (is (<= (:rocks-writes info) 6))
           (is (= #{:rocks-read :rocks-writes} (-> info keys set))))

        (let [info (capture-rocks-ops
                     (fn []
                       (p/get-incoming-transfers client "bob")
                       ))]
          (is (= info {:rocks-read 1 :rocks-iterator 1 :rocks-iterator-read 50}) "Incorrect number of RocksDB operations for fetching transfer history, likely because not subindexing the transfer history, which it must do since those can be unbounded"))
        (let [info (capture-rocks-ops
                     (fn []
                       (p/get-outgoing-transfers client "alice")
                       ))]
          (is (= info {:rocks-read 1 :rocks-iterator 1 :rocks-iterator-read 50}) "Incorrect number of RocksDB operations for fetching transfer history, likely because not subindexing the transfer history, which it must do since those can be unbounded"))
        (check-balance! client tasks)
        ))))
