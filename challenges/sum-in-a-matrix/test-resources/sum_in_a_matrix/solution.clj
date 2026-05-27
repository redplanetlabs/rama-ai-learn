(ns sum-in-a-matrix.solution
  "Reference implementation for sum-in-a-matrix challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

;; Sort each row descending, then for each column take the max across rows
;; and sum those maxes.
(deframafn matrix-sum [*nums]
  (count *nums :> *num-rows)
  ;; Sort each row descending into a vector of vectors
  (loop<- [*i 0 *sorted-rows [] :> *sr]
    (<<if (< *i *num-rows)
      (nth *nums *i :> *row)
      (sort *row :> *sorted)
      (vec (reverse *sorted) :> *desc-row)
      (conj *sorted-rows *desc-row :> *new-sr)
      (continue> (inc *i) *new-sr)
      (else>)
      (:> *sorted-rows)))
  ;; Get number of columns
  (count (first *sr) :> *num-cols)
  ;; For each column, find max across all rows and accumulate score
  (loop<- [*j 0 *score 0 :> *out]
    (<<if (< *j *num-cols)
      (loop<- [*i 0 *col-max 0 :> *cm]
        (<<if (< *i *num-rows)
          (nth (nth *sr *i) *j :> *val)
          (max *col-max *val :> *new-max)
          (continue> (inc *i) *new-max)
          (else>)
          (:> *col-max)))
      (+ *score *cm :> *new-score)
      (continue> (inc *j) *new-score)
      (else>)
      (:> *score)))
  (:> *out))
