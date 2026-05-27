(ns maximum-or.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [maximum-or.test-support :as support]))

;; Tests verify the agent's maximum-or deframafn.

(deftest challenge-test
  (testing "maximum-or (agent implementation)"
    (support/test-solution
     (requiring-resolve 'maximum-or.solution/maximum-or))))
