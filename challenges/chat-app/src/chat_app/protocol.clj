(ns chat-app.protocol
  "Protocol for the chat-app challenge — the client-facing surface of
   the module.")

(defprotocol ChatApp
  "Read/write surface over a Slack-like chat backend.

   `user-id` and `room-id` are Long, assigned by the module. `handle`,
   `name`, `title`, `room-name`, and `content` are String. Message id
   types are up to you.

   MENTIONS are parsed from message content: each token starting with
   `@` runs to the next whitespace or period (or end of content); a
   token whose text matches a registered handle mentions that handle's
   user. Non-matching tokens mention no one.

   Every user-initiated write other than register! is REJECTED with no
   effect when the acting user-id is not registered."
  (register! [this handle]
    "Create a new user owning the globally unique handle and return the
     new user-id (Long). Throws an informative exception when the handle
     is already registered — of concurrent register! calls racing for
     one handle, exactly one returns a user-id and the rest throw, with
     no partial effects. The new user is visible within 5 milliseconds
     to registered? and lookup-handle.")
  (set-profile! [this user-id name title]
    "Create or update user-id's profile. Replaces BOTH fields. Visible
     within 5 milliseconds.")
  (heartbeat! [this user-id]
    "Signal that user-id is online. A user is online when a heartbeat
     was received within the last 120 seconds, else offline.")
  (create-room! [this room-name]
    "Create a room with the globally unique room-name and return the new
     room-id (Long). Throws an informative exception when a room with
     that name already exists — of concurrent create-room! calls racing
     for one name, exactly one returns a room-id and the rest throw,
     with no partial effects. The new room is visible within 5
     milliseconds to lookup-room.")
  (join-room! [this user-id room-id]
    "Add user-id to the room's membership. Idempotent. Joining a room
     that was never created has no effect.")
  (leave-room! [this user-id room-id]
    "Remove user-id from the room's membership. Idempotent. The user's
     read position for the room is discarded, so a later rejoin starts
     with all current messages unread. Messages they posted remain.")
  (post-message! [this user-id room-id content]
    "Post a message to the room. The poster must currently be a member —
     a non-member post is rejected with no effect (no message, no
     mention entries, no unread increments). Mentions are parsed from
     content per the protocol-level rule above. The message is visible
     in get-room-page within 5 milliseconds. Derived views (unread
     counts, mentions, recent threads) may update asynchronously.")
  (reply-in-thread! [this user-id room-id root-message-id content]
    "Reply in the thread rooted at room message root-message-id. Same
     membership rule, rejection semantics, mention parsing, and
     visibility bounds as post-message!. Replies appear only in
     get-thread-page, never in get-room-page. A reply makes the replier
     a participant of the thread; the thread's starter is a participant
     from the start.")
  (mark-room-read! [this user-id room-id]
    "Mark every room message currently posted to room-id as read by
     user-id. Idempotent.")
  (registered? [this user-id]
    "Returns true when user-id has successfully registered.")
  (lookup-handle [this handle]
    "Returns the user-id that owns handle, or nil if unclaimed.")
  (lookup-room [this room-name]
    "Returns the room-id of the room named room-name, or nil if no such
     room exists.")
  (get-profile [this user-id]
    "Returns {:name String :title String}, or nil if never set.")
  (get-presence [this user-id]
    "Returns :online or :offline. This does not need to be 100% reliable.")
  (get-room-members [this room-id from-user-id]
    "Up to 20 members of the room, as a vector of user-ids in ascending
     order, starting strictly after from-user-id (from the smallest when
     nil). Pass the last returned user-id as the next call's cursor.
     Empty vector for an empty or nonexistent room. Must complete in
     roughly 50ms.")
  (get-online-members [this room-id from-user-id]
    "Up to 20 currently-online members of the room, as a vector of
     user-ids in ascending order, starting strictly after from-user-id
     (from the smallest when nil). Pass the last returned user-id as the
     next call's cursor. Online is defined as in get-presence and does not
     need to be completely reliable. Must complete in roughly 50ms.")
  (get-room-page [this room-id from-message-id]
    "Up to 20 most recent room messages (thread replies excluded), most
     recent first, posted before from-message-id (all if nil). Each
     entry is shaped:

       {:message-id  java.util.UUID  ;; pass back as from-message-id
        :user-id     Long            ;; poster
        :name        String          ;; poster's CURRENT profile name,
                                     ;; or nil if no profile was set
        :content     String
        :reply-count Long}           ;; replies in this message's thread

     Read work must be FIXED — independent of the room's total message
     history. Must complete in roughly 50ms.")
  (get-thread-page [this root-message-id from-message-id]
    "Up to 20 most recent replies in the thread rooted at
     root-message-id, most recent first, before from-message-id (all if
     nil). Entries shaped as in get-room-page without :reply-count. Same
     fixed-work and latency bounds.")
  (get-recent-threads [this user-id from-activity-id]
    "Up to 20 threads user-id started or replied in, most recently
     active first, restricted to threads whose latest activity is older
     than from-activity-id (all if nil). Each entry is shaped:

       {:root-message-id  java.util.UUID
        :room-id          Long
        :last-activity-id java.util.UUID ;; next page's cursor
        :reply-count      Long}

     Only a user's 200 most recently active threads are visible. Read
     work must be FIXED — independent of how many threads the user has
     ever touched and of thread sizes. Must complete in roughly 50ms.")
  (get-unread-counts [this user-id]
    "Returns {room-id count} covering every room user-id is currently a
     member of: count = number of room messages (thread replies
     excluded) posted after user-id's last mark-room-read! of that room
     (all of them if never marked). The cost per room must be O(1). Must complete in
     roughly 50ms.")
  (get-mentions-page [this user-id from-message-id]
    "Up to 20 most recent messages (room messages and thread replies)
     whose mentions included user-id, most recent first, before
     from-message-id (all if nil). Entries shaped as in get-room-page
     without :reply-count, plus :room-id Long. Read work must be FIXED —
     independent of total mention history. Must complete in roughly
     50ms."))
