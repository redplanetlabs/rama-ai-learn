(ns chat-app.module
  "Reference implementation for the chat-app challenge.

   State layout:
     stream topology \"core\" (5ms-visibility writes):
       $$handles        handle -> owning user-id            (hash by handle)
       $$users          user-id -> handle (registration)    (hash by user)
       $$room-names     room-name -> room-id                (hash by name)
       $$rooms          room-id -> name                     (hash by room)
       $$profiles       user-id -> {:name :title}           (hash by user)
       $$room-messages  room-id -> {msg-id -> {:user-id :content}}  (hash by room)
       $$thread-replies root-msg-id -> {msg-id -> {:user-id :content}} (hash by root)
     microbatch topology \"derived\" (barrier-consistent views):
       $$room-members       room-id -> #{user-id}           (hash by room)
       $$user-rooms         user-id -> #{room-id}           (hash by user)
       $$room-seq           room-id -> count of room messages ever (hash by room)
       $$read-cursors       room-id -> {user-id -> seq at last mark-read} (hash by room)
       $$room-reply-counts  room-id -> {root-msg-id -> reply count} (hash by room)
       $$thread-meta        root-msg-id -> {:room-id :reply-count}  (hash by root)
       $$thread-participants root-msg-id -> #{user-id}      (hash by root)
       $$mentions           user-id -> {msg-id -> {:room-id :sender :content}} (hash by user)
       $$rt-by-act          user-id -> {activity-msg-id -> root-msg-id} (hash by user)
       $$rt-idx             user-id -> {root-msg-id -> activity-msg-id} (hash by user)
     task global *presence: in-memory user-id -> last heartbeat millis.

   Two depots: *register-depot (hash by handle — registration claims) and
   *user-actions-depot (hash by acting user — every other client write).
   Both topologies consume *user-actions-depot and dispatch on record type
   with <<subsource; arriving on hash(user) makes the registration gate a
   local read for every action.

   Unread counts are O(1) per room: a monotonic per-room sequence number
   plus a per-(room, user) cursor set by mark-room-read!. Presence writes
   nothing durable: heartbeat! is a query-topology mutation of the task
   global, and get-presence compares timestamps at read time."
  (:use [com.rpl.rama]
        [com.rpl.rama.path])
  (:require [com.rpl.rama.ops :as ops]
            [com.rpl.rama.aggs :as aggs]
            [com.rpl.rama.test :as rtest]
            [chat-app.protocol :as p]
            [rama-challenges.harness :as harness])
  (:import [com.rpl.rama.integration TaskGlobalObject]
           [java.util HashMap UUID]))

;; Internal depot record types — an implementation detail of this module.
;; Register/RoomCreate carry a client-generated candidate id; the claim of
;; the unique handle/name decides whether the candidate becomes real.
;; RoomCreate's :user-id holds the candidate room-id purely to satisfy the
;; user-actions depot's (hash-by :user-id) partitioner — room creation has
;; no acting user and any partition works (the flow hops to hash(room-name)
;; immediately).
(defrecord Register [user-id handle])
(defrecord ProfileEvent [user-id name title])
(defrecord RoomCreate [user-id room-id room-name])
(defrecord MembershipEvent [op user-id room-id])
(defrecord MessageEvent [user-id room-id content message-id root-message-id])
(defrecord MarkRead [user-id room-id])

(def ONLINE-WINDOW-MS 30000)
(def RECENT-THREADS-CAP 200)
(def PAGE-SIZE 20)
(def MEMBER-SCAN-CHUNK 256)

(definterface IPresence
  (^void beat [^long user-id ^long now])
  (^boolean isOnline [^long user-id ^long now]))

(deftype PresenceStore [^HashMap beats]
  TaskGlobalObject
  (prepareForTask [_ _ _])
  (close [_])

  IPresence
  (beat [_ user-id now]
    (.put beats user-id now))
  (isOnline [_ user-id now]
    (if-let [t (.get beats user-id)]
      (< (- now (long t)) ONLINE-WINDOW-MS)
      false)))

(defn make-presence [] (PresenceStore. (HashMap.)))

(defn presence-beat! [^PresenceStore pr user-id]
  (.beat pr user-id (System/currentTimeMillis))
  true)

(defn presence-online? [^PresenceStore pr user-id]
  (.isOnline pr user-id (System/currentTimeMillis)))

;; Plain helpers used from dataflow.
(defn bump-meta [m room-id]
  {:room-id room-id :reply-count (inc (or (:reply-count m) 0))})
(defn gate-key
  "Acceptance-check key within $$room-messages: a room message checks its
   own id; a thread reply checks its root's id."
  [root-message-id message-id]
  (or root-message-id message-id))

(defn claim-ok?
  "A unique-name claim proceeds when the slot is free or already holds
   this event's own candidate id (stream retry of the same event)."
  [current-owner candidate-id]
  (or (nil? current-owner) (= current-owner candidate-id)))

(defn parse-mentions
  "Distinct @-mention tokens in content: each token starts at `@` and
   runs to the next whitespace or period (or end of content)."
  [content]
  (->> (re-seq #"@([^\s.]+)" (or content ""))
       (map second)
       distinct
       vec))

(defn rand-id
  "Candidate id for register!/create-room!: a random non-negative Long.
   The 2^63 space makes collisions negligible; uniqueness of the entity
   is decided by the handle/name claim, not the id."
  []
  (bit-and (.nextLong (java.util.concurrent.ThreadLocalRandom/current))
           Long/MAX_VALUE))

(defn ack-value
  "Extract the \"core\" stream's ack-return value from a foreign-append!
   result; throws an informative exception on an :error value."
  [ret op-name detail-key detail-val ok-key]
  (let [v (get ret "core")]
    (when (:error v)
      (throw (ex-info (str op-name " failed: " (name (:error v))
                          " (" (name detail-key) " " (pr-str detail-val) ")")
                      {:error (:error v) detail-key detail-val})))
    (get v ok-key)))

(defn sort-desc-by-first [tuples]
  (->> (or tuples []) (sort-by first) reverse vec))

(defn tuples->room-page [tuples]
  (mapv (fn [[mid uid nm content rc]]
          {:message-id mid :user-id uid :name nm :content content :reply-count rc})
        (sort-desc-by-first tuples)))

(defn tuples->thread-page [tuples]
  (mapv (fn [[mid uid nm content]]
          {:message-id mid :user-id uid :name nm :content content})
        (sort-desc-by-first tuples)))

(defn tuples->mentions-page [tuples]
  (mapv (fn [[mid uid nm content room-id]]
          {:message-id mid :user-id uid :name nm :content content :room-id room-id})
        (sort-desc-by-first tuples)))

(defn tuples->recent-threads [tuples]
  (mapv (fn [[act root room-id rc]]
          {:root-message-id root :room-id room-id
           :last-activity-id act :reply-count rc})
        (sort-desc-by-first tuples)))

(defmodule ChatAppModule [setup topologies]
  (declare-depot setup *register-depot (hash-by :handle))
  (declare-depot setup *user-actions-depot (hash-by :user-id))

  (declare-object setup *presence (make-presence))

  ;; ---------- Stream topology: 5ms-visibility durable writes ----------
  (let [s (stream-topology topologies "core")]
    (declare-pstate s $$handles {String Long})
    (declare-pstate s $$users {Long String})
    (declare-pstate s $$room-names {String Long})
    (declare-pstate s $$rooms {Long String})
    (declare-pstate s $$profiles
                    {Long (fixed-keys-schema {:name String :title String})})
    (declare-pstate s $$room-messages
                    {Long (map-schema UUID
                                      (fixed-keys-schema {:user-id Long :content String})
                                      {:subindex? true})})
    (declare-pstate s $$thread-replies
                    {UUID (map-schema UUID
                                      (fixed-keys-schema {:user-id Long :content String})
                                      {:subindex? true})})

    (<<sources s
      ;; register!: claim the handle on hash(handle); the winning candidate
      ;; id becomes the user-id and is ack-returned to the caller. A retry
      ;; of the same event sees its own claim and converges. Losers get an
      ;; error value (the client throws) and leave no effects.
      (source> *register-depot :> {:keys [*user-id *handle]})
      (local-select> [(keypath *handle)] $$handles :> *owner0)
      (<<if (claim-ok? *owner0 *user-id)
        (local-transform> [(keypath *handle) (termval *user-id)] $$handles)
        (ack-return> {:user-id *user-id})
        (|hash *user-id)
        (local-transform> [(keypath *user-id) (termval *handle)] $$users)
       (else>)
        (ack-return> {:error :handle-taken}))

      ;; All other client writes arrive on one depot, partitioned by the
      ;; acting user — so the registration gate is a local read for every
      ;; action. This stream handles the 5ms-visibility subset; membership
      ;; and mark-read are the derived microbatch's concern (no-op cases).
      (source> *user-actions-depot :> *action)
      (<<subsource *action
        ;; set-profile!: gated on registration; whole-record overwrite.
        (case> ProfileEvent :> {:keys [*user-id *name *title]})
        (local-select> [(keypath *user-id)] $$users :> *reg)
        (filter> (some? *reg))
        (local-transform> [(keypath *user-id)
                           (termval {:name *name :title *title})]
                          $$profiles)

        ;; create-room!: claim the room name on hash(room-name); the winning
        ;; candidate id becomes the room-id and is ack-returned.
        (case> RoomCreate :> {:keys [*room-id *room-name]})
        (|hash *room-name)
        (local-select> [(keypath *room-name)] $$room-names :> *rowner0)
        (<<if (claim-ok? *rowner0 *room-id)
          (local-transform> [(keypath *room-name) (termval *room-id)] $$room-names)
          (ack-return> {:room-id *room-id})
          (|hash *room-id)
          (local-transform> [(keypath *room-id) (termval *room-name)] $$rooms)
         (else>)
          (ack-return> {:error :room-name-taken}))

        ;; post-message! / reply-in-thread!: registration gate local, then
        ;; membership-gated durable write on the room task. Idempotent under
        ;; retry (termval keyed by the stable message-id).
        (case> MessageEvent :> {:keys [*user-id *room-id *content
                                       *message-id *root-message-id]})
        (local-select> [(keypath *user-id)] $$users :> *ureg)
        (filter> (some? *ureg))
        (|hash *room-id)
        (local-select> [(keypath *room-id) (view contains? *user-id)]
                       $$room-members :> *member?)
        (filter> *member?)
        (<<if (nil? *root-message-id)
          (local-transform> [(keypath *room-id *message-id)
                             (termval {:user-id *user-id :content *content})]
                            $$room-messages)
         (else>)
          (local-select> [(keypath *room-id *root-message-id)] $$room-messages
                         :> *root-rec)
          (filter> (some? *root-rec))
          (|hash *root-message-id)
          (local-transform> [(keypath *root-message-id *message-id)
                             (termval {:user-id *user-id :content *content})]
                            $$thread-replies))

        (case> MembershipEvent :> {:keys [*op]})
        (identity *op :> *_ignored-membership)

        (case> MarkRead :> {:keys [*room-id]})
        (identity *room-id :> *_ignored-mark-read))))

  ;; ---------- Microbatch topology: derived views ----------
  (let [mb (microbatch-topology topologies "derived")]
    (declare-pstate mb $$room-members
                    {Long (set-schema Long {:subindex? true})})
    (declare-pstate mb $$user-rooms
                    {Long (set-schema Long {:subindex? true})})
    (declare-pstate mb $$room-seq {Long Long})
    (declare-pstate mb $$read-cursors
                    {Long (map-schema Long Long {:subindex? true})})
    (declare-pstate mb $$room-reply-counts
                    {Long (map-schema UUID Long {:subindex? true})})
    (declare-pstate mb $$thread-meta
                    {UUID (fixed-keys-schema {:room-id Long :reply-count Long})})
    (declare-pstate mb $$thread-participants
                    {UUID (set-schema Long {:subindex? true})})
    (declare-pstate mb $$mentions
                    {Long (map-schema UUID
                                      (fixed-keys-schema {:room-id Long
                                                          :sender Long
                                                          :content String})
                                      {:subindex? true})})
    (declare-pstate mb $$rt-by-act
                    {Long (map-schema UUID UUID {:subindex? true})})
    (declare-pstate mb $$rt-idx
                    {Long (map-schema UUID UUID {:subindex? true})})

    (<<sources mb
      (source> *user-actions-depot :> %mb)
      (%mb :> *action)
      (<<subsource *action
        ;; join-room! / leave-room!: registration-gated on hash(user);
        ;; membership on hash(room); user's room list on hash(user).
        (case> MembershipEvent :> {:keys [*op *user-id *room-id]})
        (local-select> [(keypath *user-id)] $$users :> *reg)
        (filter> (some? *reg))
        (<<if (= :join *op)
          (|hash *room-id)
          (local-select> [(keypath *room-id)] $$rooms :> *room)
          (filter> (some? *room))
          (local-transform> [(keypath *room-id) NONE-ELEM (termval *user-id)]
                            $$room-members)
          (|hash *user-id)
          (local-transform> [(keypath *user-id) NONE-ELEM (termval *room-id)]
                            $$user-rooms)
         (else>)
          (local-transform> [(keypath *user-id) (set-elem *room-id) NONE>]
                            $$user-rooms)
          (|hash *room-id)
          (local-transform> [(keypath *room-id) (set-elem *user-id) NONE>]
                            $$room-members)
          (local-transform> [(keypath *room-id *user-id) NONE>] $$read-cursors))

        ;; mark-room-read!: membership-gated; cursor := current room seq.
        (case> MarkRead :> {:keys [*user-id *room-id]})
        (|hash *room-id)
        (local-select> [(keypath *room-id) (view contains? *user-id)]
                       $$room-members :> *member?)
        (filter> *member?)
        (local-select> [(keypath *room-id) (nil->val 0)] $$room-seq :> *seq)
        (local-transform> [(keypath *room-id *user-id) (termval *seq)]
                          $$read-cursors)

        ;; Derived views for accepted messages. A message was accepted iff
        ;; the core stream stored it; the gate re-derives acceptance from the
        ;; same membership + root-existence checks against committed state.
        (case> MessageEvent :> {:keys [*user-id *room-id *content
                                       *message-id *root-message-id]})
        (|hash *room-id)
        (local-select> [(keypath *room-id) (view contains? *user-id)]
                       $$room-members :> *member?)
        (filter> *member?)
        (gate-key *root-message-id *message-id :> *gkey)
      (local-select> [(keypath *room-id *gkey)] $$room-messages :> *grec)
      (filter> (some? *grec))
      (anchor> <accepted>)

      ;; mentions inbox (room messages and replies alike): parse @tokens
      ;; from content and resolve each against the handle registry.
      (parse-mentions *content :> *mention-handles)
      (ops/explode *mention-handles :> *mention-handle)
      (|hash *mention-handle)
      (local-select> [(keypath *mention-handle)] $$handles :> *mentioned)
      (filter> (some? *mentioned))
      (|hash *mentioned)
      (local-transform> [(keypath *mentioned *message-id)
                         (termval {:room-id *room-id
                                   :sender *user-id
                                   :content *content})]
                        $$mentions)

      (hook> <accepted>)
      (<<if (nil? *root-message-id)
        ;; room message: bump the room's unread sequence
        (local-transform> [(keypath *room-id) (nil->val 0) (term inc)]
                          $$room-seq)
       (else>)
        ;; thread reply: reply counts, participants, recent-threads fanout
        (local-transform> [(keypath *room-id *root-message-id)
                           (nil->val 0) (term inc)]
                          $$room-reply-counts)
        (get *grec :user-id :> *starter)
        (|hash *root-message-id)
        (local-select> [(keypath *root-message-id)] $$thread-meta :> *meta0)
        (bump-meta *meta0 *room-id :> *meta1)
        (local-transform> [(keypath *root-message-id) (termval *meta1)]
                          $$thread-meta)
        (local-transform> [(keypath *root-message-id) NONE-ELEM (termval *starter)]
                          $$thread-participants)
        (local-transform> [(keypath *root-message-id) NONE-ELEM (termval *user-id)]
                          $$thread-participants)
        ;; fan the activity bump to every participant's recent-threads view
        (local-select> [(keypath *root-message-id) ALL] $$thread-participants
                       :> *participant)
        (|hash *participant)
        (local-select> [(keypath *participant *root-message-id)] $$rt-idx
                       :> *old-act)
        (<<if (some? *old-act)
          (local-transform> [(keypath *participant *old-act) NONE>] $$rt-by-act))
        (local-transform> [(keypath *participant *message-id)
                           (termval *root-message-id)]
                          $$rt-by-act)
        (local-transform> [(keypath *participant *root-message-id)
                           (termval *message-id)]
                          $$rt-idx)
        ;; trim to the 200 most recently active threads
        (local-select> [(keypath *participant) (view count)] $$rt-by-act :> *cnt)
        (<<if (> *cnt RECENT-THREADS-CAP)
          (local-select> [(keypath *participant) (sorted-map-range-from-start 1) ALL]
                         $$rt-by-act :> [*oldest-act *oldest-root])
          (local-transform> [(keypath *participant *oldest-act) NONE>] $$rt-by-act)
          (local-transform> [(keypath *participant *oldest-root) NONE>] $$rt-idx)))

        ;; register!/set-profile!/create-room! carry no derived-view work.
        (case> ProfileEvent :> {:keys [*user-id]})
        (identity *user-id :> *_ignored-profile)

        (case> RoomCreate :> {:keys [*room-name]})
        (identity *room-name :> *_ignored-room-create))))

  ;; ---------- Query topologies ----------
  (<<query-topology topologies "heartbeat"
    [*user-id :> *res]
    (|hash *user-id)
    (local-select> [(keypath *user-id)] $$users :> *reg)
    (<<if (some? *reg)
      (presence-beat! *presence *user-id :> *_beat))
    (|origin)
    (identity true :> *res))

  (<<query-topology topologies "presence"
    [*user-id :> *online?]
    (|hash *user-id)
    (presence-online? *presence *user-id :> *online?)
    (|origin))

  (<<query-topology topologies "online-filter"
    [*ids :> *online]
    (ops/explode *ids :> *id)
    (|hash *id)
    (presence-online? *presence *id :> *on?)
    (filter> *on?)
    (|origin)
    (aggs/+set-agg *id :> *online))

  (<<query-topology topologies "room-page"
    [*room-id *from :> *page]
    (|hash *room-id)
    (<<if (nil? *from)
      (local-select> [(keypath *room-id) (sorted-map-range-to-end PAGE-SIZE) ALL]
                     $$room-messages :> [*mid {:keys [*user-id *content]}])
     (else>)
      (local-select> [(keypath *room-id) (sorted-map-range-to *from PAGE-SIZE) ALL]
                     $$room-messages :> [*mid {:keys [*user-id *content]}]))
    (local-select> [(keypath *room-id *mid) (nil->val 0)] $$room-reply-counts
                   :> *rc)
    (|hash *user-id)
    (local-select> [(keypath *user-id)] $$profiles :> {:keys [*name]})
    (vector *mid *user-id *name *content *rc :> *tuple)
    (|origin)
    (aggs/+vec-agg *tuple :> *tuples)
    (tuples->room-page *tuples :> *page))

  (<<query-topology topologies "thread-page"
    [*root-message-id *from :> *page]
    (|hash *root-message-id)
    (<<if (nil? *from)
      (local-select> [(keypath *root-message-id) (sorted-map-range-to-end PAGE-SIZE) ALL]
                     $$thread-replies :> [*mid {:keys [*user-id *content]}])
     (else>)
      (local-select> [(keypath *root-message-id) (sorted-map-range-to *from PAGE-SIZE) ALL]
                     $$thread-replies :> [*mid {:keys [*user-id *content]}]))
    (|hash *user-id)
    (local-select> [(keypath *user-id)] $$profiles :> {:keys [*name]})
    (vector *mid *user-id *name *content :> *tuple)
    (|origin)
    (aggs/+vec-agg *tuple :> *tuples)
    (tuples->thread-page *tuples :> *page))

  (<<query-topology topologies "recent-threads"
    [*user-id *from :> *page]
    (|hash *user-id)
    (<<if (nil? *from)
      (local-select> [(keypath *user-id) (sorted-map-range-to-end PAGE-SIZE) ALL]
                     $$rt-by-act :> [*act *root])
     (else>)
      (local-select> [(keypath *user-id) (sorted-map-range-to *from PAGE-SIZE) ALL]
                     $$rt-by-act :> [*act *root]))
    (|hash *root)
    (local-select> [(keypath *root)] $$thread-meta
                   :> {:keys [*room-id *reply-count]})
    (vector *act *root *room-id *reply-count :> *tuple)
    (|origin)
    (aggs/+vec-agg *tuple :> *tuples)
    (tuples->recent-threads *tuples :> *page))

  (<<query-topology topologies "unread-counts"
    [*user-id :> *counts]
    (|hash *user-id)
    (local-select> [(keypath *user-id) ALL] $$user-rooms :> *room-id)
    (|hash *room-id)
    (local-select> [(keypath *room-id) (nil->val 0)] $$room-seq :> *seq)
    (local-select> [(keypath *room-id *user-id) (nil->val 0)] $$read-cursors
                   :> *cursor)
    (- *seq *cursor :> *count)
    (|origin)
    (aggs/+map-agg *room-id *count :> *counts))

  (<<query-topology topologies "mentions-page"
    [*user-id *from :> *page]
    (|hash *user-id)
    (<<if (nil? *from)
      (local-select> [(keypath *user-id) (sorted-map-range-to-end PAGE-SIZE) ALL]
                     $$mentions :> [*mid {:keys [*room-id *sender *content]}])
     (else>)
      (local-select> [(keypath *user-id) (sorted-map-range-to *from PAGE-SIZE) ALL]
                     $$mentions :> [*mid {:keys [*room-id *sender *content]}]))
    (|hash *sender)
    (local-select> [(keypath *sender)] $$profiles :> {:keys [*name]})
    (vector *mid *sender *name *content *room-id :> *tuple)
    (|origin)
    (aggs/+vec-agg *tuple :> *tuples)
    (tuples->mentions-page *tuples :> *page)))

(defn make-client
  [ipc]
  (let [m (get-module-name ChatAppModule)
        register-depot (foreign-depot ipc m "*register-depot")
        user-actions-depot (foreign-depot ipc m "*user-actions-depot")
        users-p (foreign-pstate ipc m "$$users")
        handles-p (foreign-pstate ipc m "$$handles")
        room-names-p (foreign-pstate ipc m "$$room-names")
        profiles-p (foreign-pstate ipc m "$$profiles")
        room-members-p (foreign-pstate ipc m "$$room-members")
        heartbeat-q (foreign-query ipc m "heartbeat")
        presence-q (foreign-query ipc m "presence")
        online-filter-q (foreign-query ipc m "online-filter")
        room-page-q (foreign-query ipc m "room-page")
        thread-page-q (foreign-query ipc m "thread-page")
        recent-threads-q (foreign-query ipc m "recent-threads")
        unread-q (foreign-query ipc m "unread-counts")
        mentions-q (foreign-query ipc m "mentions-page")
        mb-cnt (atom 0)
        ;; every user-actions append is one record processed by the
        ;; "derived" microbatch — count them all for the barrier.
        ua-append! (fn [rec]
                     (swap! mb-cnt inc)
                     (foreign-append! user-actions-depot rec :ack))]
    (reify
      p/ChatApp
      (register! [_ handle]
        (-> (foreign-append! register-depot (->Register (rand-id) handle) :ack)
            (ack-value "register!" :handle handle :user-id)))
      (set-profile! [_ user-id name title]
        (ua-append! (->ProfileEvent user-id name title)))
      (heartbeat! [_ user-id]
        (foreign-invoke-query heartbeat-q user-id)
        nil)
      (create-room! [_ room-name]
        (let [candidate (rand-id)]
          (-> (ua-append! (->RoomCreate candidate candidate room-name))
              (ack-value "create-room!" :room-name room-name :room-id))))
      (join-room! [_ user-id room-id]
        (ua-append! (->MembershipEvent :join user-id room-id)))
      (leave-room! [_ user-id room-id]
        (ua-append! (->MembershipEvent :leave user-id room-id)))
      (post-message! [_ user-id room-id content]
        (ua-append! (->MessageEvent user-id room-id content
                                    (ops/random-uuid7) nil)))
      (reply-in-thread! [_ user-id room-id root-message-id content]
        (ua-append! (->MessageEvent user-id room-id content
                                    (ops/random-uuid7) root-message-id)))
      (mark-room-read! [_ user-id room-id]
        (ua-append! (->MarkRead user-id room-id)))
      (registered? [_ user-id]
        (some? (foreign-select-one (keypath user-id) users-p {:pkey user-id})))
      (lookup-handle [_ handle]
        (foreign-select-one (keypath handle) handles-p {:pkey handle}))
      (lookup-room [_ room-name]
        (foreign-select-one (keypath room-name) room-names-p {:pkey room-name}))
      (get-profile [_ user-id]
        (foreign-select-one (keypath user-id) profiles-p {:pkey user-id}))
      (get-presence [_ user-id]
        (if (foreign-invoke-query presence-q user-id) :online :offline))
      (get-room-members [_ room-id from-user-id]
        (vec (foreign-select-one
              [(keypath room-id)
               (subselect (sorted-set-range-from
                           (or from-user-id Long/MIN_VALUE)
                           {:max-amt PAGE-SIZE :inclusive? false})
                          ALL)]
              room-members-p {:pkey room-id})))
      (get-online-members [_ room-id from-user-id]
        (loop [cursor (or from-user-id Long/MIN_VALUE)
               acc []]
          (let [chunk (vec (foreign-select-one
                            [(keypath room-id)
                             (subselect (sorted-set-range-from
                                         cursor
                                         {:max-amt MEMBER-SCAN-CHUNK
                                          :inclusive? false})
                                        ALL)]
                            room-members-p {:pkey room-id}))
                online (or (foreign-invoke-query online-filter-q chunk) #{})
                acc (into acc (filter online chunk))]
            (if (or (>= (count acc) PAGE-SIZE)
                    (< (count chunk) MEMBER-SCAN-CHUNK))
              (vec (take PAGE-SIZE acc))
              (recur (last chunk) acc)))))
      (get-room-page [_ room-id from-message-id]
        (or (foreign-invoke-query room-page-q room-id from-message-id) []))
      (get-thread-page [_ root-message-id from-message-id]
        (or (foreign-invoke-query thread-page-q root-message-id from-message-id) []))
      (get-recent-threads [_ user-id from-activity-id]
        (or (foreign-invoke-query recent-threads-q user-id from-activity-id) []))
      (get-unread-counts [_ user-id]
        (or (foreign-invoke-query unread-q user-id) {}))
      (get-mentions-page [_ user-id from-message-id]
        (or (foreign-invoke-query mentions-q user-id from-message-id) []))

      harness/Synchronizable
      ;; "core" is a stream whose appends all use full ack, so those writes
      ;; are complete when the protocol calls return. The asynchronous work
      ;; is the "derived" microbatch topology; wait on its processed count.
      (wait-for-processing! [_]
        (rtest/wait-for-microbatch-processed-count ipc m "derived" @mb-cnt)))))

(defn create-module
  []
  {:module ChatAppModule
   :wrap-client make-client})
