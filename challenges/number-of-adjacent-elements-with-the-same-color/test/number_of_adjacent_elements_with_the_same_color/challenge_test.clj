(ns number-of-adjacent-elements-with-the-same-color.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [number-of-adjacent-elements-with-the-same-color.test-support :as support]))

;; Tests verify the agent's color-the-array deframafn.

(deftest challenge-test
  (testing "color-the-array (agent implementation)"
    (support/test-solution
     (requiring-resolve 'number-of-adjacent-elements-with-the-same-color.solution/color-the-array))))
