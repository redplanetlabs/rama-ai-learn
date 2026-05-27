(ns auction-module.protocol
  "Protocol definition for the auction-module challenge.")

(defprotocol AuctionModule
  "Auction system with listings, bids, expiration, and winner tracking."
  (list-item! [this seller-id item expiration-time-millis]
    "Create a new auction listing. 'item' is a string and expiration-time-millis is a timestamp.
     Returns the listing-id for the new listing. Listings should be readable within 5 milliseconds of
     invoking this method.")
  (bid! [this bidder-id listing-id amount]
    "Place a bid on a valid listing. If the bidder has a previous bid, only updates if
     the new amount is higher. Bids should be visible within 5 milliseconds of
     invoking this method.")
  (get-listings [this seller-id]
    "Get listings as a vector of hashmaps {:listing-id :item :expiration-time-millis},
     ordered by creation time.")
  (get-bids [this listing-id]
    "Get current bids for listing as a vector of {:bidder-id :amount} hashmaps.
     One entry per bidder with their latest bid amount.")
  (get-highest-bid [this listing-id]
    "Get highest bid as a hashmap {:user-id :amount}, or nil.")
  (get-notifications [this user-id]
    "Get vector of auction results ordered chronologically, with each being hashmaps containing:

    Seller sold a listing: {:type :sale :listing-id ... :winner-id ... :amount ...}
    Seller's auction expired with no bids: {:type :no-sale :listing-id ...}
    Bidder won a listing: {:type :won :listing-id ... :amount ...}
    Bidder lost a listing: {:type :lost :listing-id ...}
    ")
  (process-expirations! [this]
    "Testing-only method. In production, expiration processing is driven
     automatically by the passage of time. This method exists solely for IPC
     testing — it triggers expiration processing and blocks until all currently
     expired auctions have been handled. The underlying module design must still
     use automatic time-driven expiration."))
