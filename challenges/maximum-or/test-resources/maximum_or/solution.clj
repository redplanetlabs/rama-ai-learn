(ns maximum-or.solution
  "Reference implementation for maximum-or challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

;; Apply all k doublings to one element to maximize bitwise OR.
;; For each candidate, compute prefix-or | (nums[i] << k) | suffix-or.
(deframafn maximum-or [*nums *k]
  (count *nums :> *n)
  ;; Build suffix-OR array: suf[i] = OR of nums[i+1..n-1]
  (vec (repeat *n 0) :> *suf-init)
  (loop<- [*i (dec *n) *cur-or 0 *suffixes *suf-init :> *suf]
    (<<if (>= *i 0)
      (assoc *suffixes *i *cur-or :> *new-suffixes)
      (nth *nums *i :> *val)
      (bit-or *cur-or *val :> *next-or)
      (continue> (dec *i) *next-or *new-suffixes)
      (else>)
      (:> *suffixes)))
  ;; For each element, try applying all k shifts to it
  (loop<- [*i 0 *prefix-or 0 *best 0 :> *result]
    (<<if (< *i *n)
      (nth *nums *i :> *val)
      (nth *suf *i :> *s-or)
      (bit-shift-left *val *k :> *shifted)
      (bit-or *prefix-or *shifted *s-or :> *candidate)
      (max *best *candidate :> *new-best)
      (bit-or *prefix-or *val :> *new-prefix)
      (continue> (inc *i) *new-prefix *new-best)
      (else>)
      (:> *best)))
  (:> *result))
