(ns auction-module.functional-test-support
  "Functional private tests for auction-module."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]
   [rama-challenges.shared :as shared]
   [auction-module.protocol :as p])
  (:import [com.rpl.rama.helpers TopologyUtils]))

(defn- exact-bids [bids]
  (is (= (count bids) (count (set bids))) "bids should have no duplicates")
  (set bids))

(defn- gen-nonexistent-id
  "Generate a value of the same type as sample-id that won't match any real ID."
  [sample-id]
  (cond
    (instance? Long sample-id)
    (long (- Long/MAX_VALUE (rand-int 1000000000)))

    (instance? String sample-id)
    (str (java.util.UUID/randomUUID))

    (instance? java.util.UUID sample-id)
    (java.util.UUID/randomUUID)

    (record? sample-id)
    (reduce-kv (fn [r k v] (assoc r k (gen-nonexistent-id v)))
               sample-id
               sample-id)

    :else
    (throw (ex-info "Unsupported ID type for gen-nonexistent-id" {:type (type sample-id) :value sample-id}))))

(defn test-module-functional
  [create-module-fn]
  (with-redefs [shared/REPLACE-TICK-DEPOTS true]
    (with-open [_ (TopologyUtils/startSimTime)]
      (harness/with-module [client create-module-fn]

        (testing "no listings initially"
          (is (= [] (p/get-listings client 1)))
          (is (= [] (p/get-notifications client 1))))

        (testing "process-expirations! with nothing to expire"
          (p/process-expirations! client))

        (testing "create listings and get them back in order"
          (let [lid1 (p/list-item! client 1 "Guitar" 60000)
                lid2 (p/list-item! client 1 "Amp" 90000)]
            (is (some? lid1) "list-item! should return a listing-id")
            (is (some? lid2))
            (is (not= lid1 lid2) "listing-ids from same seller must be unique")
            (is (= [{:listing-id lid1 :item "Guitar" :expiration-time-millis 60000}
                    {:listing-id lid2 :item "Amp" :expiration-time-millis 90000}]
                   (p/get-listings client 1)))))

        (testing "other seller has no listings"
          (is (= [] (p/get-listings client 999))))

        (let [lid (p/list-item! client 10 "Piano" 60000)]

          (testing "no bids initially"
            (is (= [] (p/get-bids client lid)))
            (is (nil? (p/get-highest-bid client lid))))

          (let [fake-lid (gen-nonexistent-id lid)]
            (testing "get-bids on nonexistent listing"
              (is (= [] (p/get-bids client fake-lid))))

            (testing "get-highest-bid on nonexistent listing"
              (is (nil? (p/get-highest-bid client fake-lid)))))

          (testing "place a bid and verify content"
            (p/bid! client 20 lid 100)
            (is (= #{{:bidder-id 20 :amount 100}} (exact-bids (p/get-bids client lid))))
            (is (= {:user-id 20 :amount 100} (p/get-highest-bid client lid))))

          (testing "higher bid replaces highest"
            (p/bid! client 30 lid 200)
            (is (= #{{:bidder-id 20 :amount 100}
                      {:bidder-id 30 :amount 200}}
                   (exact-bids (p/get-bids client lid))))
            (is (= {:user-id 30 :amount 200} (p/get-highest-bid client lid))))

          (testing "lower bid is stored but does not change highest"
            (p/bid! client 40 lid 50)
            (is (= #{{:bidder-id 20 :amount 100}
                      {:bidder-id 30 :amount 200}
                      {:bidder-id 40 :amount 50}}
                   (exact-bids (p/get-bids client lid))))
            (is (= {:user-id 30 :amount 200} (p/get-highest-bid client lid))))

          (testing "equal bid does not override existing highest"
            (p/bid! client 50 lid 200)
            (is (= {:user-id 30 :amount 200} (p/get-highest-bid client lid))))

          (testing "same bidder bids again with higher amount"
            (p/bid! client 20 lid 300)
            (is (= {:user-id 20 :amount 300} (p/get-highest-bid client lid))))

          (testing "expire auction with bids"
            (harness/wait-for-processing! client)
            (TopologyUtils/advanceSimTime 120000)
            (p/process-expirations! client)

            (testing "seller gets sale notification"
              (is (= [{:type :sale :listing-id lid :winner-id 20 :amount 300}]
                     (p/get-notifications client 10))))

            (testing "winner gets won notification only, not lost"
              (is (= [{:type :won :listing-id lid :amount 300}]
                     (p/get-notifications client 20))))

            (testing "all losers get exactly one lost notification"
              (doseq [loser-id [30 40 50]]
                (is (= [{:type :lost :listing-id lid}]
                       (p/get-notifications client loser-id))
                    (str "loser " loser-id " notification mismatch")))))

          (testing "bids on expired listing are rejected"
            (let [bid-count-before (count (p/get-bids client lid))]
              (p/bid! client 60 lid 500)
              (is (= bid-count-before (count (p/get-bids client lid))))
              (is (= {:user-id 20 :amount 300} (p/get-highest-bid client lid))))))

        (testing "auction with no bids expires with no-sale"
          (let [lid (p/list-item! client 100 "Drums" 180000)]
            (harness/wait-for-processing! client)
            (TopologyUtils/advanceSimTime 120000)
            (p/process-expirations! client)
            (is (= [{:type :no-sale :listing-id lid}]
                   (p/get-notifications client 100)))))

        (testing "duplicate process-expirations! does not re-notify"
          (p/process-expirations! client)
          (is (= 1 (count (p/get-notifications client 10)))
              "seller 10 should still have exactly 1 notification after extra expiration tick")
          (is (= 1 (count (p/get-notifications client 20)))
              "winner 20 should still have exactly 1 notification after extra expiration tick"))

        (testing "multiple sellers have independent listings"
          (let [lid-a (p/list-item! client 200 "Bass" 300000)
                lid-b (p/list-item! client 201 "Sax" 300000)]
            (is (= 1 (count (p/get-listings client 200))))
            (is (= 1 (count (p/get-listings client 201))))
            (is (not= lid-a lid-b))))

        ;; Sim time is now 240000 (120000 + 120000 from previous advances)
        (testing "multiple auctions expire independently"
          (let [lid1 (p/list-item! client 300 "Item1" 300000)
                lid2 (p/list-item! client 301 "Item2" 600000)]
            (p/bid! client 310 lid1 100)
            (p/bid! client 320 lid2 200)
            (harness/wait-for-processing! client)
            ;; Advance to 340000 — expires lid1 (300000) but not lid2 (600000)
            (TopologyUtils/advanceSimTime 100000)
            (p/process-expirations! client)
            (is (= [{:type :sale :listing-id lid1 :winner-id 310 :amount 100}]
                   (p/get-notifications client 300)))
            (is (= [] (p/get-notifications client 301)))
            ;; Advance to 640000 — now expires lid2 (600000)
            (TopologyUtils/advanceSimTime 300000)
            (p/process-expirations! client)
            (is (= [{:type :sale :listing-id lid2 :winner-id 320 :amount 200}]
                   (p/get-notifications client 301)))))

        ;; Sim time is now 640000
        (testing "user receives notifications as both seller and bidder"
          (let [lid-sell (p/list-item! client 400 "Violin" 700000)]
            ;; Expire user 400's auction with no bids — user 400 gets :no-sale
            (harness/wait-for-processing! client)
            (TopologyUtils/advanceSimTime 100000)
            (p/process-expirations! client)
            (is (= [{:type :no-sale :listing-id lid-sell}]
                   (p/get-notifications client 400)))

            ;; Now user 400 bids on user 401's item
            (let [lid-other (p/list-item! client 401 "Cello" 800000)]
              (p/bid! client 400 lid-other 150)
              (harness/wait-for-processing! client)
              (TopologyUtils/advanceSimTime 200000)
              (p/process-expirations! client)
              ;; User 400 now has :no-sale from before + :won from this auction
              (is (= [{:type :no-sale :listing-id lid-sell}
                      {:type :won :listing-id lid-other :amount 150}]
                     (p/get-notifications client 400))))))))))
