(ns auction-module.module
  "Reference implementation for the auction-module challenge.
   Demonstrates multi-depot event processing with bid validation,
   expiration handling, and winner determination."
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.ops :as ops]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [auction-module.protocol]
            [rama-challenges.harness :as harness]
            [rama-challenges.shared :as shared])
  (:import [com.rpl.rama.helpers
             ModuleUniqueIdPState
             TopologyScheduler]
            [java.util UUID]))


(defrecord Listing [user-id listing-partial-id item expiration-time-millis])
(defrecord Bid [bidder-id listing-id amount])
(defrecord ListingId [user-id listing-partial-id])

(definterface AuctionResult
  (toResultMap [user-id]))
(defrecord OwnerSoldResult [listing-partial-id winner-id amount]
  AuctionResult
  (toResultMap [this user-id]
    {:type :sale
     :listing-id (->ListingId user-id listing-partial-id)
     :winner-id winner-id
     :amount amount
     }))
(defrecord OwnerNoSaleResult [listing-partial-id]
  AuctionResult
  (toResultMap [this user-id]
    {:type :no-sale
     :listing-id (->ListingId user-id listing-partial-id)
     }))
(defrecord WonAuctionResult [listing-id amount]
  AuctionResult
  (toResultMap [this _]
    {:type :won
     :listing-id listing-id
     :amount amount
     }))
(defrecord LostAuctionResult [listing-id]
  AuctionResult
  (toResultMap [this _]
    {:type :lost
     :listing-id listing-id
     }))


(defn owner-notification [listing-partial-id winner-id amount]
  (if winner-id
    (->OwnerSoldResult listing-partial-id winner-id amount)
    (->OwnerNoSaleResult listing-partial-id)
    ))

(defn winner-notification [user-id listing-partial-id amount]
  (->WonAuctionResult (->ListingId user-id listing-partial-id) amount))

(defn loser-notification [user-id listing-partial-id]
  (->LostAuctionResult (->ListingId user-id listing-partial-id)))

(defn nested-listing-user-id [data]
  (-> data :listing-id :user-id))

(defmodule AuctionModule [setup topologies]
  (declare-depot setup *listing-depot (hash-by :user-id))
  (declare-depot setup *bid-depot (hash-by nested-listing-user-id))

  (if shared/REPLACE-TICK-DEPOTS
    (declare-depot setup *expire-tick :random {:global? true})
    (declare-tick-depot setup *expire-tick 30000))

  (let [s (stream-topology topologies "auction")]
    (declare-pstate s $$user-listings {Long ; user ID
                                        (map-schema UUID ; listing partial ID
                                                    (fixed-keys-schema
                                                      {:item String
                                                       :expiration-time-millis Long})
                                                    {:subindex? true})})
    (declare-pstate s $$listing-bidders {UUID ; listing partial ID
                                          (map-schema Long ; user ID
                                                      Long ; amount
                                                      {:subindex? true})})
    (declare-pstate s $$listing-top-bid {UUID ; listing partial ID
                                          (fixed-keys-schema {:user-id Long
                                                              :amount Long})})
    (declare-pstate s $$user-bids {Long (map-schema ListingId
                                                    Long ; amount
                                                    {:subindex? true})})

    (<<sources s
      (source> *listing-depot :> {:keys [*user-id *listing-partial-id *item *expiration-time-millis] :as *listing})
      (local-transform> [(keypath *user-id *listing-partial-id)
                         (termval {:item *item
                                   :expiration-time-millis *expiration-time-millis})]
                        $$user-listings)

      (source> *bid-depot :> {:keys [*bidder-id *listing-id *amount]})
      (identity *listing-id :> {:keys [*user-id *listing-partial-id]})
      (local-select> (keypath *listing-partial-id) $$finished-listings :> *finished?)
      (filter> (not *finished?))
      (local-transform> [(keypath *listing-partial-id *bidder-id) (termval *amount)]
                        $$listing-bidders)
      (local-transform> [(keypath *listing-partial-id)
                         (selected? :amount (nil->val 0) (pred< *amount))
                         (termval {:user-id *bidder-id :amount *amount})]
                        $$listing-top-bid)
      (|hash *bidder-id)
      (local-transform> [(keypath *bidder-id *listing-id) (termval *amount)] $$user-bids)
      ))
  (let [mb (microbatch-topology topologies "expirations")
        scheduler (TopologyScheduler. "$$scheduler")]
    (declare-pstate mb $$finished-listings {UUID Boolean})
    (declare-pstate mb $$notifications {Long (vector-schema AuctionResult {:subindex? true})})
    (.declarePStates scheduler mb)

    (<<sources mb
      (source> *listing-depot :> %microbatch)
      (%microbatch :> {:keys [*listing-partial-id *user-id *expiration-time-millis]})
      (->ListingId *user-id *listing-partial-id :> *listing-id)
      (java-macro! (.scheduleItem scheduler "*expiration-time-millis" "*listing-id"))

      (source> *expire-tick :> %microbatch)
      (%microbatch)
      (java-macro!
        (.handleExpirations
          scheduler
          "*listing-id"
          "*current-time-millis"
          (java-block<-
            (identity *listing-id :> {:keys [*user-id *listing-partial-id]})
            (local-transform> [(keypath *listing-partial-id) (termval true)]
                              $$finished-listings)
            (local-select> (keypath *listing-partial-id)
                           $$listing-top-bid
                           :> {*winner-id :user-id *amount :amount})
            (local-transform> [(keypath *user-id)
                               AFTER-ELEM
                               (termval (owner-notification *listing-partial-id *winner-id *amount))]
                              $$notifications)
            (local-select> [(keypath *listing-partial-id) MAP-KEYS]
                           $$listing-bidders
                           {:allow-yield? true}
                           :> *bidder-id)
            (|hash *bidder-id)
            (<<if (= *bidder-id *winner-id)
              (winner-notification *user-id *listing-partial-id *amount :> *notification)
             (else>)
              (loser-notification *user-id *listing-partial-id :> *notification))
            (local-transform> [(keypath *bidder-id)
                               AFTER-ELEM
                               (termval *notification)]
                              $$notifications)
            ))))))


(defn make-client
  "Creates a protocol client for the auction module."
  [ipc]
  (let [module-name (get-module-name AuctionModule)
        listing-depot (foreign-depot ipc module-name "*listing-depot")
        bid-depot (foreign-depot ipc module-name "*bid-depot")
        expire-tick-depot (when shared/REPLACE-TICK-DEPOTS
                            (foreign-depot ipc module-name "*expire-tick"))

        user-listings (foreign-pstate ipc module-name "$$user-listings")
        listing-bidders (foreign-pstate ipc module-name "$$listing-bidders")
        listing-top-bid (foreign-pstate ipc module-name "$$listing-top-bid")
        notifications (foreign-pstate ipc module-name "$$notifications")

        mb-cnt (atom 0)]
    (reify auction-module.protocol/AuctionModule
      (list-item! [_ seller-id item expiration-time-millis]
        (swap! mb-cnt inc)
        (let [listing-partial-id (ops/random-uuid7)]
          (foreign-append! listing-depot (->Listing seller-id listing-partial-id item expiration-time-millis))
          (->ListingId seller-id listing-partial-id)))
      (bid! [_ bidder-id listing-id amount]
        (foreign-append! bid-depot (->Bid bidder-id listing-id amount)))
      (get-listings [_ seller-id]
         (let [l (foreign-select [(keypath seller-id) ALL] user-listings)]
           (mapv (fn [[listing-partial-id data]]
                   (assoc data :listing-id (->ListingId seller-id listing-partial-id)))
                 l)))
      (get-bids [_ listing-id]
        (let [v (foreign-select [(keypath (:listing-partial-id listing-id)) ALL]
                        listing-bidders
                        {:pkey (:user-id listing-id)})]
          (mapv (fn [[bidder-id amount]]
                  {:bidder-id bidder-id :amount amount})
                v)))
      (get-highest-bid [_ listing-id]
        (foreign-select-one (keypath (:listing-partial-id listing-id))
                            listing-top-bid
                            {:pkey (:user-id listing-id)}))
      (get-notifications [_ user-id]
        (mapv
          (fn [^AuctionResult ar]
            (.toResultMap ar user-id))
          (foreign-select [(keypath user-id) ALL] notifications)))
      (process-expirations! [_]
        (swap! mb-cnt inc)
        (foreign-append! expire-tick-depot nil)
        (rtest/wait-for-microbatch-processed-count ipc module-name "expirations" @mb-cnt))

      harness/Synchronizable
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc module-name "expirations" @mb-cnt)))))

(defn create-module
  "Returns the module descriptor for the auction-module challenge."
  []
  {:module      AuctionModule
   :wrap-client make-client})
