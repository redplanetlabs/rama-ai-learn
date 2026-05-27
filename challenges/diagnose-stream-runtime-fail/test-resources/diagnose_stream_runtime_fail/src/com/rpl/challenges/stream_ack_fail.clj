(ns com.rpl.challenges.stream-ack-fail
  "Reference challenge module that deploys successfully but has a persistent
   runtime failure for a poison-pill payment event."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

(defmodule StreamAckFailModule
  {:module-name "StreamAckFailModule"}
  [setup topologies]
  (declare-depot setup *payments :random)

  (let [s (stream-topology topologies "payments")]
    (declare-pstate s $$payment-totals {String Long})

    (<<sources s
      (source> *payments :> {:keys [*user-id *amount]})
      ;; Poison-pill behavior: malformed :amount causes runtime failure before
      ;; the event tree can complete, so foreign-append! with :ack never
      ;; successfully returns for that record.
      (Long/parseLong *amount :> *parsed-amount)
      (|hash *user-id)
      (local-select> [(keypath *user-id) (nil->val 0)] $$payment-totals :> *current)
      (+ *current *parsed-amount :> *next)
      (local-transform> [(keypath *user-id) (termval *next)] $$payment-totals)
      (ack-return> {"status" "ok"
                    "user-id" *user-id
                    "amount" *parsed-amount}))))
