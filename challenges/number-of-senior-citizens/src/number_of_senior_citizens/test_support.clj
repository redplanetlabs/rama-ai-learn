(ns number-of-senior-citizens.test-support
  "Shared test logic for the number-of-senior-citizens challenge."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'number-of-senior-citizens.solution))
  (testing "count-seniors"
      (testing "case 0"
        (is (= 2 (solve-fn ["7868190130M7522" "5303914400F9211" "9273338290F4010"]))))

      (testing "case 1"
        (is (= 0 (solve-fn ["1313579440F2036" "2921522980M5644"]))))))
