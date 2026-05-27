(ns number-of-adjacent-elements-with-the-same-color.solution
  "Reference implementation for number-of-adjacent-elements-with-the-same-color challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

;; O(q) approach: maintain a running count of adjacent same-color pairs.
;; On each query, check neighbors before and after the color change.
(deframafn color-the-array [*n *queries]
  (vec (repeat *n 0) :> *nums-init)
  (count *queries :> *q-len)
  (loop<- [*qi 0 *nums *nums-init *adj 0 *result [] :> *out]
    (<<if (< *qi *q-len)
      (nth *queries *qi :> *query)
      (nth *query 0 :> *idx)
      (nth *query 1 :> *color)
      (nth *nums *idx :> *old-color)
      ;; Subtract matches with left neighbor before change
      (<<if (and> (> *idx 0) (not= *old-color 0))
        (nth *nums (dec *idx) :> *left)
        (<<if (= *old-color *left)
          (dec *adj :> *adj1)
          (else>)
          (identity *adj :> *adj1))
        (else>)
        (identity *adj :> *adj1))
      ;; Subtract matches with right neighbor before change
      (<<if (and> (< *idx (dec *n)) (not= *old-color 0))
        (nth *nums (inc *idx) :> *right)
        (<<if (= *old-color *right)
          (dec *adj1 :> *adj2)
          (else>)
          (identity *adj1 :> *adj2))
        (else>)
        (identity *adj1 :> *adj2))
      ;; Apply the color change
      (assoc *nums *idx *color :> *new-nums)
      ;; Add matches with left neighbor after change
      (<<if (> *idx 0)
        (nth *new-nums (dec *idx) :> *new-left)
        (<<if (and> (not= *color 0) (= *color *new-left))
          (inc *adj2 :> *adj3)
          (else>)
          (identity *adj2 :> *adj3))
        (else>)
        (identity *adj2 :> *adj3))
      ;; Add matches with right neighbor after change
      (<<if (< *idx (dec *n))
        (nth *new-nums (inc *idx) :> *new-right)
        (<<if (and> (not= *color 0) (= *color *new-right))
          (inc *adj3 :> *adj4)
          (else>)
          (identity *adj3 :> *adj4))
        (else>)
        (identity *adj3 :> *adj4))
      (conj *result *adj4 :> *new-result)
      (continue> (inc *qi) *new-nums *adj4 *new-result)
      (else>)
      (:> *result)))
  (:> *out))
