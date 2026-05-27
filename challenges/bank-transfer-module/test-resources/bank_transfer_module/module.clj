(ns bank-transfer-module.module
  "Reference implementation for bank-transfer-module challenge.
   Demonstrates fund transfers with cross-partition coordination."
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [rama-challenges.harness :as harness]
            [bank-transfer-module.protocol]))

(defrecord Deposit [user-id amt])
(defrecord Transfer [transfer-id from-user-id to-user-id amt])

(defmodule BankTransfer [setup topologies]
  (declare-depot setup *deposit-depot (hash-by :user-id))
  (declare-depot setup *transfer-depot (hash-by :from-user-id))

  (let [mb (microbatch-topology topologies "banking")]

    (declare-pstate mb $$funds {String Long})

    (declare-pstate mb
                    $$outgoing-transfers
                    {String (map-schema String
                                        (fixed-keys-schema {:to-user-id String
                                                            :amt Long
                                                            :success? Boolean})
                                        {:subindex? true})})

    (declare-pstate mb
                    $$incoming-transfers
                    {String (map-schema String
                                        (fixed-keys-schema {:from-user-id String
                                                            :amt Long
                                                            :success? Boolean})
                                        {:subindex? true})})

    (<<sources mb
       ;; Deposit processing
       (source> *deposit-depot :> %microbatch)
       (%microbatch :> {:keys [*user-id *amt]})
       (+compound $$funds {*user-id (aggs/+sum *amt)})

       ;; Transfer processing
       (source> *transfer-depot :> %microbatch)
       (%microbatch :> {:keys [*transfer-id *from-user-id *to-user-id *amt]})

       (local-select> [(keypath *from-user-id) (nil->val 0)] $$funds :> *from-balance)
       (>= *from-balance *amt :> *success?)

       (<<if *success?
         (local-transform> [(keypath *from-user-id) (termval (- *from-balance *amt))]
                           $$funds))

       (local-transform> [(keypath *from-user-id *transfer-id)
                          (termval {:to-user-id *to-user-id
                                    :amt *amt
                                    :success? *success?})]
                         $$outgoing-transfers)

       (|hash *to-user-id)

       (<<if *success?
         (+compound $$funds {*to-user-id (aggs/+sum *amt)}))

       (local-transform> [(keypath *to-user-id *transfer-id)
                          (termval {:from-user-id *from-user-id
                                    :amt *amt
                                    :success? *success?})]
                         $$incoming-transfers))))


(defn make-client
  "Creates a protocol client for the bank transfer module."
  [ipc]
  (let [module-name (get-module-name BankTransfer)
        deposit-depot  (foreign-depot ipc module-name "*deposit-depot")
        transfer-depot (foreign-depot ipc module-name "*transfer-depot")
        funds (foreign-pstate ipc module-name "$$funds")
        incoming-transfers (foreign-pstate ipc module-name "$$incoming-transfers")
        outgoing-transfers (foreign-pstate ipc module-name "$$outgoing-transfers")
        cnt (atom 0)]
    (reify bank-transfer-module.protocol/BankTransferModule
      (deposit! [_ user-id amt]
        (swap! cnt inc)
        (foreign-append! deposit-depot (->Deposit user-id amt)))
      (transfer! [_ transfer-id from-user-id to-user-id amt]
        (swap! cnt inc)
        (foreign-append! transfer-depot (->Transfer transfer-id from-user-id to-user-id amt)))
      (get-balance [_ user-id]
        (foreign-select-one [(keypath user-id) (nil->val 0)] funds))
      (get-outgoing-transfers [_ user-id]
        (foreign-select [(keypath user-id) ALL] outgoing-transfers))
      (get-incoming-transfers [_ user-id]
        (foreign-select [(keypath user-id) ALL] incoming-transfers))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc module-name "banking" @cnt)))))

(defn create-module
  []
  {:module      BankTransfer
   :wrap-client make-client})
