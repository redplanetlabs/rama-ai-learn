(ns fill-the-gaps.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [fill-the-gaps.test-support :as support]))

;; Tests verify the agent's solve deframafn.

(deftest challenge-test
  (testing "solve (agent implementation)"
    (support/test-solution
     (requiring-resolve 'fill-the-gaps.solution/solve))))
