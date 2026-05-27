(ns bank-transfer-module.functional-test-support
  "Functional private tests for bank-transfer-module (moved from public tests)."
  (:require
   [bank-transfer-module.protocol :as p]
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]))

(defn test-module-functional
  [create-module-fn]
  (let [result (create-module-fn)
        module (:module result)
        wrap-client (:wrap-client result)
        tasks (rand-nth [2 4])
        threads (+ 2 (rand-int (dec tasks)))]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module {:tasks tasks :threads threads})
      (let [client (wrap-client ipc)
            sync! (fn [] (harness/wait-for-processing! client))
            alice "0"
            bob "1"
            charlie "2"]
        (testing "deposit!"
          (testing "adds to user balance"
            (p/deposit! client alice 200)
            (sync!)
            (is (= 200 (p/get-balance client alice))
                "alice should have 200"))

          (testing "accumulates across deposits"
            (p/deposit! client alice 50)
            (sync!)
            (is (= 250 (p/get-balance client alice))
                "alice: 200 + 50 = 250"))

          (testing "tracks balances independently"
            (p/deposit! client bob 100)
            (p/deposit! client charlie 75)
            (sync!)
            (is (= 250 (p/get-balance client alice))
                "alice unchanged at 250")
            (is (= 100 (p/get-balance client bob))
                "bob should have 100")
            (is (= 75 (p/get-balance client charlie))
                "charlie should have 75")))

        (testing "transfer!"
          (testing "with sufficient funds"
            (testing "debits sender and credits receiver"
              (p/transfer! client "t1" alice bob 50)
              (sync!)
              (is (= 200 (p/get-balance client alice))
                  "alice: 250 - 50 = 200")
              (is (= 150 (p/get-balance client bob))
                  "bob: 100 + 50 = 150")))

          (testing "with exact balance"
            (testing "succeeds when amt equals balance"
              (p/transfer! client "t2" charlie bob 75)
              (sync!)
              (is (= 0 (p/get-balance client charlie))
                  "charlie: 75 - 75 = 0")
              (is (= 225 (p/get-balance client bob))
                  "bob: 150 + 75 = 225")))

          (testing "with insufficient funds"
            (testing "leaves balances unchanged"
              (p/transfer! client "t3" alice bob 999)
              (sync!)
              (is (= 200 (p/get-balance client alice))
                  "alice unchanged at 200")
              (is (= 225 (p/get-balance client bob))
                  "bob unchanged at 225")))

          (testing "from zero-balance user"
            (testing "fails gracefully"
              (p/transfer! client "t4" charlie bob 10)
              (sync!)
              (is (= 0 (p/get-balance client charlie))
                  "charlie still at 0")
              (is (= 225 (p/get-balance client bob))
                  "bob unchanged at 225")))

          (testing "from unknown user"
            (testing "fails gracefully"
              (p/transfer! client "t5" "999" bob 10)
              (sync!)
              (is (= 0 (p/get-balance client "999"))
                  "unknown user still at 0")
              (is (= 225 (p/get-balance client bob))
                  "bob unchanged at 225")))

          (testing "after failure"
            (testing "processes subsequent transfers"
              (p/transfer! client "t6" alice charlie 30)
              (sync!)
              (is (= 170 (p/get-balance client alice))
                  "alice: 200 - 30 = 170")
              (is (= 30 (p/get-balance client charlie))
                  "charlie: 0 + 30 = 30"))))

        (testing "get-outgoing-transfers"
          (testing "returns vector of pairs"
            (let [result (p/get-outgoing-transfers client alice)]
              (is (vector? result)
                  "result should be a vector")
              (is (every? #(and (vector? %) (= 2 (count %))) result)
                  "each entry should be a [id info] pair")))

          (testing "includes all outgoing transfers"
            (let [out (into {} (p/get-outgoing-transfers client alice))]
              (is (= 3 (count out))
                  "alice has 3 outgoing: t1 t3 t6")
              (is (= {:to-user-id bob :amt 50 :success? true}
                     (get out "t1")))
              (is (= {:to-user-id bob :amt 999 :success? false}
                     (get out "t3")))
              (is (= {:to-user-id charlie :amt 30 :success? true}
                     (get out "t6")))))

          (testing "records both successful and failed"
            (let [charlie-out (into {} (p/get-outgoing-transfers client charlie))]
              (is (= 2 (count charlie-out))
                  "charlie has 2 outgoing: t2 t4")
              (is (= {:to-user-id bob :amt 75 :success? true}
                     (get charlie-out "t2")))
              (is (= {:to-user-id bob :amt 10 :success? false}
                     (get charlie-out "t4")))))

          (testing "returns empty for user with no outgoing"
            (let [bob-out (p/get-outgoing-transfers client bob)]
              (is (= [] bob-out)
                  "bob has no outgoing transfers"))))

        (testing "get-incoming-transfers"
          (testing "returns vector of pairs"
            (let [result (p/get-incoming-transfers client bob)]
              (is (vector? result)
                  "result should be a vector")
              (is (every? #(and (vector? %) (= 2 (count %))) result)
                  "each entry should be a [id info] pair")))

          (testing "includes successful and failed"
            (let [bob-in (into {} (p/get-incoming-transfers client bob))]
              (is (= 5 (count bob-in))
                  "bob has 5 incoming: t1 t2 t3 t4 t5")
              (is (= {:from-user-id alice :amt 50 :success? true}
                     (get bob-in "t1")))
              (is (= {:from-user-id charlie :amt 75 :success? true}
                     (get bob-in "t2")))
              (is (= {:from-user-id alice :amt 999 :success? false}
                     (get bob-in "t3")))
              (is (= {:from-user-id charlie :amt 10 :success? false}
                     (get bob-in "t4")))))

          (testing "includes transfers from unknown user"
            (let [bob-in (into {} (p/get-incoming-transfers client bob))]
              (is (= {:from-user-id "999" :amt 10 :success? false}
                     (get bob-in "t5")))))

          (testing "tracks transfers to receiver"
            (let [charlie-in (into {} (p/get-incoming-transfers client charlie))]
              (is (= 1 (count charlie-in))
                  "charlie has 1 incoming: t6")
              (is (= {:from-user-id alice :amt 30 :success? true}
                     (get charlie-in "t6")))))

          (testing "returns empty for user with none"
            (is (= [] (p/get-incoming-transfers client alice))
                "alice has no incoming transfers")))

        (testing "get-balance"
          (testing "returns 0 for unknown user"
            (is (= 0 (p/get-balance client "8888"))
                "unknown user balance should be 0")))

        (testing "get-outgoing-transfers for unknown user"
          (is (= [] (p/get-outgoing-transfers client "8888"))
              "unknown user has no outgoing"))

        (testing "get-incoming-transfers for unknown user"
          (is (= [] (p/get-incoming-transfers client "8888"))
              "unknown user has no incoming"))
        ))))
