(ns overall-winner.private-test-support
  "Shared test logic for the overall-winner.private-test-support namespace."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'overall-winner.solution))
  (let [cases [{:input "90\nATTTTATATATTATTAATATATTAATTATTAAATTAAATTATTTATTAATAAAAATATTATTTTAATAAAAATTAAAAAAAATATTTTTT\n" :expected "A"}
             {:input "91\nAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n" :expected "A"}
             {:input "92\nAATAAAAAAATATAAAAAAAAATAAAAAAATAAATAAAAAAAAAAAAAAAAATAAAAAAAAAATAAAAAATAAAATAAAATAAAAAATATAA\n" :expected "A"}
             {:input "92\nATTTAAATTTTAAAAATAAATTTAAAATTAAAATTAAAAATAATTTTAATTTATATTTATTTAATTTTATAAATAATTTTTATATAATATTA\n" :expected "T"}
             {:input "92\nATTTTTATTTTTATTATTTTAATATTTAAATTAATTTATTAAATTTTATTTTAAATATATTTAAATAATATTATTAAATTAATATTTAAATA\n" :expected "T"}
             {:input "92\nATATTTAAATAATTTTATTAATTATTTAATATTTTTATTATTTAATTTAATTTAAATATATAATATAAAAAATTATAAATTATAAAATAAAT\n" :expected "A"}
             {:input "93\nTTAAAATTTTATAATAAAAAAAATAATAAATTTTATTTAATAATAAATAAAAAAAAAAAATTTTTATTATATTTATATTTTTAAAAATTATAA\n" :expected "A"}
             {:input "93\nTTTATTATTATATTTTTATATTTTATTTTTTAAATTTTATAATTTTTATTTTTTATTTATATTTATTTAAATTTTATATTTATTATATTTTTA\n" :expected "T"}
             {:input "96\nTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT\n" :expected "T"}
             {:input "97\nTTTTTTTTTATTTATTTTTTTATTTTTTTTTATTAAATTTATTTTTTTTTTTTTATTTTTTTATTTTTAATTTTTTTTTATTTTTTATTTTTTTTTT\n" :expected "T"}
             {:input "98\nTTAATATTATATAATTAAAATAATTTATATAAATTAATAATTATTTATAATATATAATAAATAATAATTTATAATAATTTTTATATTTATTATAATTA\n" :expected "T"}
             {:input "100\nAAAAAAAAAAAAAAAAAAAAAAAATTTAAAAATATTATAAAATATAATAATTAAAAAAAAATATTTATTTATAAAATATAAAATAATTAAATATATAATA\n" :expected "A"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
