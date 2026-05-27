(ns auction-module.performance-test-support
  "Performance tests for auction-module"
  (:require
   [auction-module.protocol :as p]
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [rama-challenges.shared :as shared])
  (:import [com.rpl.rama.helpers TopologyUtils]))


(defn capture-iterator-reads [f]
  (let [count (atom 0)]
    (rtest/with-event-hook
      (fn [event-type _data]
        (when (= event-type :rocks-iterator-read)
          (swap! count inc)))
      (f))
    @count))

(defn failed-streaming [f]
  (let [failed? (atom false)
        ret (rtest/with-event-hook
              (fn [event-type _data]
                (when (and (= event-type :streaming-complete)
                           (compare-and-set! failed? false true))
                  :fail))
              (f))]
    ret))

(defn test-module-performance
  [create-module-fn]
  (with-redefs [shared/REPLACE-TICK-DEPOTS true]
    (with-open [_ (TopologyUtils/startSimTime)]
      (let [result (create-module-fn)
            module (:module result)
            wrap-client (:wrap-client result)
            tasks (rand-nth [2 4])
            threads (+ 2 (rand-int (dec tasks)))]
        (with-open [ipc (rtest/create-ipc)]
          (rtest/launch-module! ipc module {:tasks tasks :threads threads})
          (let [client1 (wrap-client ipc)
                client2 (wrap-client ipc)
                ids1 (atom [])
                ids2 (atom [])
                list1! (fn [seller-id item exp]
                         (let [lid (p/list-item! client1 seller-id item exp)]
                           (swap! ids1 conj lid)
                           lid))
                list2! (fn [seller-id item exp]
                         (let [lid (p/list-item! client2 seller-id item exp)]
                           (swap! ids2 conj lid)
                           lid))]

            ;; 1. Verify list-item! uses stream topology
            (let [topo-types (atom #{})]
              (rtest/with-event-hook
                (fn [event-type data]
                  (when (= event-type :topology-event)
                    (swap! topo-types conj (:type data))))
                (list1! 1 "Guitar" 999999999))
              (is (contains? @topo-types :stream)
                  "list-item! must use a stream topology for millisecond visibility"))

            ;; 2. Verify bid! uses stream topology
            (let [lid (list1! 1 "Amp" 999999999)
                  topo-types (atom #{})]
              (rtest/with-event-hook
                (fn [event-type data]
                  (when (= event-type :topology-event)
                    (swap! topo-types conj (:type data))))
                (p/bid! client1 20 lid 100))
              (is (contains? @topo-types :stream)
                  "bid! must use a stream topology for millisecond visibility"))

            ;; 3. Induce stream failure on list-item!, verify exactly-once notifications
            ;; The stream retry on listing creation can produce duplicate
            ;; internal depot records (e.g., schedule-depot). When the listing
            ;; later expires, notifications must not be duplicated.
            (let [lid (failed-streaming #(list1! 800 "FailItem" 60000))]
              (p/bid! client1 810 lid 100)
              (p/bid! client1 820 lid 200)
              (harness/wait-for-processing! client1)
              ;; this catches agent improperly using streaming for expirations
              (failed-streaming #(p/process-expirations! client1))
              (TopologyUtils/advanceSimTime 61000)
              ;; - this still goes through properly even though the failure caused mb-cnt in client to be off by one
              (failed-streaming #(p/process-expirations! client1))
              ;; Seller should get exactly one notification despite the retry
              (is (= 1 (count (p/get-notifications client1 800)))
                  "seller must receive exactly one notification even after stream retry on list-item!")
              (let [seller-notif (first (p/get-notifications client1 800))]
                (is (= :sale (:type seller-notif))))
              ;; Winner gets exactly one :won
              (is (= 1 (count (p/get-notifications client1 810)))
                  "winner must receive exactly one notification even after stream retry on list-item!")
              ;; Loser gets exactly one :lost
              (is (= 1 (count (p/get-notifications client1 820)))
                  "loser must receive exactly one notification even after stream retry on list-item!"))

            ;; 4. Verify subindexing via iterator usage on reads and use of allow-yield?
            (let [sl1 (list1! 900 "Sub-A" 200000)
                  _   (list1! 900 "Sub-B" 200000)
                  _   (list1! 900 "Sub-C" 200000)
                  allow-yield?-atom (atom false)]
              (p/bid! client1 910 sl1 100)
              (p/bid! client1 920 sl1 200)
              (p/bid! client1 930 sl1 50)
              (harness/wait-for-processing! client1)

              (rtest/with-event-hook
                (fn [event-type data]
                  (when (and (= :local-select event-type) (:allow-yield? data))
                    (reset! allow-yield?-atom true)))
                (p/process-expirations! client1)
                (TopologyUtils/advanceSimTime 140000)
                (p/process-expirations! client1))
              (is @allow-yield?-atom)

              (is (pos? (capture-iterator-reads #(p/get-listings client1 900)))
                  "get-listings must use RocksDB iterators (listings per seller must be subindexed)")
              (is (pos? (capture-iterator-reads #(p/get-bids client1 sl1)))
                  "get-bids must use RocksDB iterators (bids per listing must be subindexed)")
              (is (pos? (capture-iterator-reads #(p/get-notifications client1 900)))
                  "get-notifications must use RocksDB iterators (notifications per user must be subindexed)"))

            ;; 5. Generate many listings from both clients (far-future expiration)
            ;;  - so it here since the count tracking in the client is off when using multiple clients
            (dotimes [i 10]
              (list1! (+ 500 i) (str "C1-Item-" i) 999999999)
              (list2! (+ 600 i) (str "C2-Item-" i) 999999999))

            ;; 5. Verify all listing IDs across both clients are unique
            (let [all-ids (concat @ids1 @ids2)]
              (is (= (count all-ids) (count (set all-ids)))
                  "all listing IDs across both clients must be unique"))
            ))))))
