(ns power-of-heroes.solution
  "Reference implementation for power-of-heroes challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

;; After sorting, for each nums[i] as the max, the weighted sum of possible
;; minimums is dp[i] = 2*dp[i-1] + nums[i], giving contribution nums[i]^2 * (nums[i] + dp[i-1]).
(deframafn sum-of-power [*nums]
  (sort *nums :> *sorted)
  (vec *sorted :> *sv)
  (count *sv :> *n)
  (loop<- [*i 0 *dp 0 *result 0 :> *out]
    (<<if (< *i *n)
      (nth *sv *i :> *v)
      (* *v *v :> *v2)
      (mod *v2 1000000007 :> *v2m)
      (+ *v *dp :> *v-plus-dp)
      (mod *v-plus-dp 1000000007 :> *vpd)
      (* *v2m *vpd :> *contrib)
      (mod *contrib 1000000007 :> *c)
      (+ *result *c :> *r1)
      (mod *r1 1000000007 :> *new-result)
      (* 2 *dp :> *dp2)
      (+ *dp2 *v :> *dp3)
      (mod *dp3 1000000007 :> *new-dp)
      (continue> (inc *i) *new-dp *new-result)
      (else>)
      (:> *result)))
  (:> *out))
