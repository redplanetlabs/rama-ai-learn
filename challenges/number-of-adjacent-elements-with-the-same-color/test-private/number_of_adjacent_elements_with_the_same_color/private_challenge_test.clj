(ns number-of-adjacent-elements-with-the-same-color.private-challenge-test
  "Runs hidden/private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [number-of-adjacent-elements-with-the-same-color.private-test-support :as support]))

(deftest private-challenge-test
  (testing "color-the-array (agent implementation, private tests)"
    (support/test-solution
     (requiring-resolve 'number-of-adjacent-elements-with-the-same-color.solution/color-the-array))))
