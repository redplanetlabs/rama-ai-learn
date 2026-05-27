(ns attack.private-test-support
  "Shared test logic for the attack.private-test-support namespace."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'attack.solution))
  (let [cases [{:input "7270 116\n" :expected "63"}
             {:input "59977464294637 1862647\n" :expected "32200124"}
             {:input "11533765830482604 817419533590\n" :expected "14110"}
             {:input "103838087894477201 4\n" :expected "25959521973619301"}
             {:input "324013942553097064 767290441\n" :expected "422283304"}
             {:input "159058673781516990 835315590\n" :expected "190417461"}
             {:input "238734688664061268 297816499\n" :expected "801616732"}
             {:input "406777939015149177 2\n" :expected "203388969507574589"}
             {:input "623064468778408115 5\n" :expected "124612893755681623"}
             {:input "802035354240686040 925744958032207328\n" :expected "1"}
             {:input "390896634924972442 366843596946451619\n" :expected "2"}
             {:input "883473210852275310 58937130685266794\n" :expected "15"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
