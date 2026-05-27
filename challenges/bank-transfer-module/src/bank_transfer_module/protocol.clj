(ns bank-transfer-module.protocol)

(defprotocol BankTransferModule
  "Protocol for banking operations with fund transfers"
  (deposit! [this user-id amt]
    "Deposit amt to user-id's balance")
  (transfer! [this transfer-id from-user-id to-user-id amt]
    "Transfer amt from from-user-id to to-user-id")
  (get-balance [this user-id]
    "Returns current balance for user-id or 0 if no deposits.")
  (get-outgoing-transfers [this user-id]
    "Returns vector of [transfer-id transfer-info] pairs for user-id's outgoing transfers.
     transfer-info is map of form {:to-user-id <user-id> :amt <amt> :success? <boolean>}")
  (get-incoming-transfers [this user-id]
    "Returns vector of [transfer-id transfer-info] pairs for user-id's incoming transfers.
     transfer-info is map of form {:from-user-id <user-id> :amt <amt> :success? <boolean>}"))
