(ns number-of-senior-citizens.challenge-test
  "Verifies the agent's deframafn implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [number-of-senior-citizens.test-support :as support]))

;; Tests verify the agent's count-seniors deframafn.

(deftest challenge-test
  (testing "count-seniors (agent implementation)"
    (support/test-solution
     (requiring-resolve 'number-of-senior-citizens.solution/count-seniors))))
