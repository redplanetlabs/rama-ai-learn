(ns neighboring-bitwise-xor.solution
  "Reference implementation for neighboring-bitwise-xor challenge."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

;; XOR of all derived elements must be 0.
;; Each original[i] appears exactly twice in the expansion of XOR(derived),
;; so they cancel out, leaving 0 iff a valid original exists.
(deframafn does-valid-array-exist [*derived]
  (reduce bit-xor 0 *derived :> *xor-total)
  (zero? *xor-total :> *answer)
  (:> *answer))
