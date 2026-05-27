(ns impartial-gift.solution
  "Reference implementation for impartial-gift challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [clojure.string :as str]))

;;; Parse a whitespace-separated line into a vector of longs.
(deframafn parse-longs [*line]
  (str/split (str/trim *line) #"\s+" :> *parts)
  (loop<- [*i 0 *acc [] :> *result]
          (<<if (= *i (count *parts))
                (:> *acc)
                (else>)
                (parse-long (nth *parts *i) :> *n)
                (conj *acc *n :> *acc2)
                (continue> (inc *i) *acc2)))
  (:> *result))

;;; Binary search for the rightmost index i in sorted vec v where v[i] <= hi.
;;; Returns nil if no element in [lo, hi] exists.
(deframafn max-in-range [*v *lo *hi]
  (loop<- [*left 0 *right (dec (count *v)) *best -1 :> *idx]
          (<<if (> *left *right)
                (:> *best)
                (else>)
                (quot (+ *left *right) 2 :> *mid)
                (nth *v *mid :> *val)
                (<<if (<= *val *hi)
                      (continue> (inc *mid) *right *mid)
                      (else>)
                      (continue> *left (dec *mid) *best))))
  (<<if (= *idx -1)
        (:> nil)
        (else>)
        (nth *v *idx :> *max-b)
        (<<if (>= *max-b *lo)
              (:> *max-b)
              (else>)
              (:> nil))))

(deframafn solve [*input]
  (str/split-lines (str/trim *input) :> *lines)
  (str/split (nth *lines 0) #"\s+" :> *header)
  (parse-long (nth *header 2) :> *D)
  ;; Build sorted vector of B for binary search
  (parse-longs (nth *lines 2) :> *b-nums)
  (sort *b-nums :> *b-seq)
  (vec *b-seq :> *B)
  ;; For each a in A, find the max b in [a-D, a+D]
  (parse-longs (nth *lines 1) :> *a-nums)
  (loop<- [*i 0 *best -1 :> *answer]
          (<<if (= *i (count *a-nums))
                (:> *best)
                (else>)
                (nth *a-nums *i :> *a)
                (- *a *D :> *lo)
                (+ *a *D :> *hi)
                (max-in-range *B *lo *hi :> *max-b)
                (<<if (nil? *max-b)
                      (continue> (inc *i) *best)
                      (else>)
                      (+ *a *max-b :> *sum)
                      (max *best *sum :> *new-best)
                      (continue> (inc *i) *new-best))))
  (str *answer :> *result)
  (:> *result))
