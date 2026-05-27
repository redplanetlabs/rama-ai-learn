(ns find-the-distinct-difference-array.solution
  "Reference implementation for find-the-distinct-difference-array challenge."
  (:require
   [com.rpl.rama :refer :all]
   [com.rpl.rama.path :refer :all]))

(deframafn distinct-difference-array [*nums]
  (count *nums :> *n)
  (vec *nums :> *v)
  (loop<- [*i 0 *result [] :> *out]
    (<<if (< *i *n)
      (subvec *v 0 (inc *i) :> *prefix)
      (subvec *v (inc *i) *n :> *suffix)
      (count (set *prefix) :> *pd)
      (count (set *suffix) :> *sd)
      (- *pd *sd :> *diff)
      (conj *result *diff :> *new-result)
      (continue> (inc *i) *new-result)
      (else>)
      (:> *result)))
  (:> *out))
