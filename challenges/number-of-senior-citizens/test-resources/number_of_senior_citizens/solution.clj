(ns number-of-senior-citizens.solution
  "Reference implementation for number-of-senior-citizens challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

(deframafn count-seniors [*details]
  (vec *details :> *v)
  (count *v :> *n)
  (loop<- [*i 0 *count 0 :> *out]
    (<<if (< *i *n)
      (nth *v *i :> *s)
      (subs *s 11 13 :> *age-str)
      (Integer/parseInt *age-str :> *age)
      (<<if (> *age 60)
        (continue> (inc *i) (inc *count))
        (else>)
        (continue> (inc *i) *count))
      (else>)
      (:> *count)))
  (:> *out))
