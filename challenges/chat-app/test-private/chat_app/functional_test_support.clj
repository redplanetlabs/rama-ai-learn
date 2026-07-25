(ns chat-app.functional-test-support
  "Functional private tests for chat-app. These tests exercise the public
   ChatApp protocol only and make no assumption about the module's
   internal partitioning or topology strategy, so any correct
   implementation passes."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [chat-app.protocol :as p]))

(defn- launch-args []
  (let [tasks (rand-nth [2 4])
        threads (+ 2 (rand-int (inc (- tasks 2))))]
    {:tasks tasks :threads threads}))

(defn- thrown-msg [f]
  (try (f) nil (catch Exception e (or (.getMessage e) "thrown"))))

(defn- page-all
  "Exhaust a cursor-paginated read. next-cursor-fn extracts the cursor to
   pass for the following page from the last entry of a page."
  [read-fn next-cursor-fn]
  (loop [cursor nil acc []]
    (let [page (read-fn cursor)]
      (if (empty? page)
        acc
        (recur (next-cursor-fn (last page)) (into acc page))))))

(defn test-module-identity-and-rooms
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module (launch-args))
      (let [c (wrap-client ipc)]

        (testing "registration returns fresh Long user-ids, immediately visible"
          (let [ua (p/register! c "alice")
                ub (p/register! c "bob")]
            (is (instance? Long ua))
            (is (instance? Long ub))
            (is (not= ua ub))
            ;; no barrier: register!'s visibility bound is 5ms
            (is (p/registered? c ua))
            (is (p/registered? c ub))
            (is (= ua (p/lookup-handle c "alice")))
            (is (= ub (p/lookup-handle c "bob")))))

        (testing "unknown identities read as absent"
          (is (not (p/registered? c 123456789)))
          (is (nil? (p/lookup-handle c "unclaimed")))
          (is (nil? (p/lookup-room c "no-such-room")))
          (is (nil? (p/get-profile c 123456789))))

        (testing "duplicate handle throws informative exception, no effect"
          (let [ua (p/lookup-handle c "alice")
                msg (thrown-msg #(p/register! c "alice"))]
            (is (some? msg) "second register! of same handle must throw")
            (is (re-find #"alice" msg) "exception names the offending handle")
            (is (= ua (p/lookup-handle c "alice")) "owner unchanged")))

        (testing "concurrent register! races: exactly one winner"
          (let [n 8
                results (->> (range n)
                             (mapv (fn [_] (future (try {:ok (p/register! c "raced")}
                                                        (catch Exception e {:err e})))))
                             (mapv #(deref % 30000 {:err :timeout})))
                winners (filterv :ok results)]
            (is (= 1 (count winners)) "exactly one concurrent register! wins")
            (is (= (:ok (first winners)) (p/lookup-handle c "raced")))))

        (testing "create-room! returns room-id, lookup-room resolves, dup name throws"
          (let [r (p/create-room! c "general")]
            (is (instance? Long r))
            ;; no barrier: create-room!'s visibility bound is 5ms
            (is (= r (p/lookup-room c "general")))
            (let [msg (thrown-msg #(p/create-room! c "general"))]
              (is (some? msg))
              (is (re-find #"general" msg)))
            (is (= r (p/lookup-room c "general")) "owner unchanged after failed create")))

        (testing "concurrent create-room! races: exactly one winner, room usable"
          (let [n 8
                results (->> (range n)
                             (mapv (fn [_] (future (try {:ok (p/create-room! c "raced-room")}
                                                        (catch Exception e {:err e})))))
                             (mapv #(deref % 30000 {:err :timeout})))
                winners (filterv :ok results)
                r (:ok (first winners))]
            (is (= 1 (count winners)))
            (is (= r (p/lookup-room c "raced-room")))
            (let [u (p/register! c "roomtester")]
              (p/join-room! c u r)
              (harness/wait-for-processing! c)
              (is (= [u] (p/get-room-members c r nil)) "the winning room accepts members"))))

        (testing "profiles: set/get, whole-record replace, registration gate"
          (let [ua (p/lookup-handle c "alice")]
            (p/set-profile! c ua "Alice" "Engineer")
            (is (= {:name "Alice" :title "Engineer"} (p/get-profile c ua)))
            (p/set-profile! c ua "Alicia" "Manager")
            (is (= {:name "Alicia" :title "Manager"} (p/get-profile c ua))
                "set-profile! replaces both fields")
            (p/set-profile! c 987654321 "Ghost" "Nobody")
            (harness/wait-for-processing! c)
            (is (nil? (p/get-profile c 987654321))
                "unregistered set-profile! is rejected")))))))

(defn test-module-membership-and-presence
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module (launch-args))
      (let [c (wrap-client ipc)
            users (mapv #(p/register! c (str "user" %)) (range 25))
            room (p/create-room! c "lobby")
            side (p/create-room! c "side")]

        (testing "join is idempotent; membership pages ascending with cursor"
          (doseq [u users] (p/join-room! c u room))
          (p/join-room! c (first users) room) ;; duplicate join
          (harness/wait-for-processing! c)
          (let [sorted-users (vec (sort users))
                page1 (p/get-room-members c room nil)
                page2 (p/get-room-members c room (last page1))]
            (is (= 20 (count page1)))
            (is (= (take 20 sorted-users) page1) "first page: 20 smallest user-ids")
            (is (= (drop 20 sorted-users) page2) "second page: remaining 5")
            (is (= [] (p/get-room-members c room (last page2))) "past the end")))

        (testing "join of nonexistent room and by unregistered user: no effect"
          (p/join-room! c (first users) 424242424242)
          (p/join-room! c 987654321 room)
          (harness/wait-for-processing! c)
          (is (= [] (p/get-room-members c 424242424242 nil)))
          (is (= 25 (count (page-all #(p/get-room-members c room %) identity)))))

        (testing "leave removes membership; leave is idempotent"
          (let [u (first (sort users))]
            (p/leave-room! c u room)
            (p/leave-room! c u room)
            (harness/wait-for-processing! c)
            (let [members (page-all #(p/get-room-members c room %) identity)]
              (is (= 24 (count members)))
              (is (not (some #{u} members))))
            (p/join-room! c u room)
            (harness/wait-for-processing! c)))

        (testing "presence: offline until heartbeat; unregistered heartbeats rejected"
          (is (= :offline (p/get-presence c (first users))))
          (p/heartbeat! c (first users))
          (harness/wait-for-processing! c)
          (is (= :online (p/get-presence c (first users))))
          (is (= :offline (p/get-presence c (second users))))
          (p/heartbeat! c 987654321)
          (harness/wait-for-processing! c)
          (is (= :offline (p/get-presence c 987654321))))

        (testing "online members: only online, ascending, cursor-paginated"
          (let [online (vec (sort (take 23 users)))
                offline (drop 23 users)]
            (doseq [u online] (p/heartbeat! c u))
            (harness/wait-for-processing! c)
            (let [page1 (p/get-online-members c room nil)
                  page2 (p/get-online-members c room (last page1))]
              (is (= 20 (count page1)))
              (is (= (take 20 online) page1))
              (is (= (drop 20 online) page2))
              (is (= [] (p/get-online-members c room (last page2))))
              (doseq [u offline]
                (is (not (some #{u} (concat page1 page2)))
                    "users without heartbeats never appear")))))

        (testing "online members reflect membership, not just presence"
          (let [u (first (sort users))]
            (p/leave-room! c u room)
            (harness/wait-for-processing! c)
            (is (not (some #{u} (p/get-online-members c room nil)))
                "online but departed users are not listed")
            (is (= [] (p/get-online-members c side nil))
                "room with no members has no online members")))))))

(defn test-module-messaging
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module (launch-args))
      (let [c (wrap-client ipc)
            ua (p/register! c "alice")
            ub (p/register! c "bob")
            uc (p/register! c "carol")
            ud (p/register! c "dave")
            room (p/create-room! c "main")
            den (p/create-room! c "den")]
        (doseq [u [ua ub uc]] (p/join-room! c u room))
        (p/join-room! c ua den)
        (harness/wait-for-processing! c)

        (testing "post visible in room page within the call's ack (no barrier)"
          (p/post-message! c ua room "first post")
          (let [page (p/get-room-page c room nil)]
            (is (= ["first post"] (mapv :content page)))
            (is (= [ua] (mapv :user-id page)))
            (is (= [nil] (mapv :name page)) "no profile set -> :name nil")))

        (testing "poster names resolve to CURRENT profile at read time"
          (p/set-profile! c ua "Alice" "Eng")
          (is (= ["Alice"] (mapv :name (p/get-room-page c room nil))))
          (p/set-profile! c ua "Alicia" "Eng")
          (is (= ["Alicia"] (mapv :name (p/get-room-page c room nil)))
              "a rename retroactively changes what pages show"))

        (testing "non-member and unregistered posts: no effect anywhere"
          (p/post-message! c ud room "intruder")     ;; registered, not a member
          (p/post-message! c 987654321 room "ghost") ;; unregistered
          (harness/wait-for-processing! c)
          (is (= ["first post"] (mapv :content (p/get-room-page c room nil))))
          (is (= {room 1} (p/get-unread-counts c ub))
              "rejected posts don't bump unread"))

        (testing "room page pagination: most recent first, 20 + cursor"
          (doseq [i (range 24)]
            (p/post-message! c ub room (str "msg" i)))
          (harness/wait-for-processing! c)
          (let [expected (into (vec (for [i (range 23 -1 -1)] (str "msg" i)))
                               ["first post"])
                page1 (p/get-room-page c room nil)
                page2 (p/get-room-page c room (:message-id (last page1)))]
            (is (= 20 (count page1)))
            (is (= (take 20 expected) (mapv :content page1)))
            (is (= (drop 20 expected) (mapv :content page2)))
            (is (= [] (p/get-room-page c room (:message-id (last page2)))))))

        (testing "threads: replies in thread page only; reply-count; pagination"
          (let [root (:message-id (last (p/get-room-page c room
                                                         (:message-id (last (p/get-room-page c room nil))))))]
            ;; root = "first post" (the oldest message)
            (doseq [i (range 24)]
              (p/reply-in-thread! c uc room root (str "reply" i)))
            (harness/wait-for-processing! c)
            (is (= 25 (count (page-all (fn [cur] (p/get-room-page c room cur))
                                       :message-id)))
                "replies never appear in the room page")
            (let [tpage1 (p/get-thread-page c root nil)
                  tpage2 (p/get-thread-page c root (:message-id (last tpage1)))]
              (is (= 20 (count tpage1)))
              (is (= (for [i (range 23 3 -1)] (str "reply" i)) (mapv :content tpage1)))
              (is (= (for [i (range 3 -1 -1)] (str "reply" i)) (mapv :content tpage2))))
            (let [full (page-all (fn [cur] (p/get-room-page c room cur)) :message-id)
                  entry (first (filter #(= root (:message-id %)) full))]
              (is (= 24 (:reply-count entry)) "room page carries live reply-count"))
            (testing "reply to nonexistent root: no effect"
              (p/reply-in-thread! c uc room (java.util.UUID/randomUUID) "void")
              (harness/wait-for-processing! c)
              (is (= 24 (:reply-count
                         (first (filter #(= root (:message-id %))
                                        (page-all (fn [cur] (p/get-room-page c room cur))
                                                  :message-id)))))))))))))

(defn test-module-recent-threads-unread-mentions
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module (launch-args))
      (let [c (wrap-client ipc)
            ua (p/register! c "alice")
            ub (p/register! c "bob")
            uc (p/register! c "carol")
            room (p/create-room! c "main")
            other (p/create-room! c "other")]
        (doseq [u [ua ub uc]] (p/join-room! c u room))
        (p/join-room! c ua other)
        (harness/wait-for-processing! c)

        (testing "recent threads: participants, ordering, activity bump, cursor"
          ;; two threads: t1 (by alice), t2 (by bob); carol replies to both
          (p/post-message! c ua room "t1 root")
          (p/post-message! c ub room "t2 root")
          (harness/wait-for-processing! c)
          (let [[e2 e1] (p/get-room-page c room nil)
                t1 (:message-id e1)
                t2 (:message-id e2)]
            (p/reply-in-thread! c uc room t1 "r-t1")
            (harness/wait-for-processing! c)
            (p/reply-in-thread! c uc room t2 "r-t2")
            (harness/wait-for-processing! c)
            (is (= [t2 t1] (mapv :root-message-id (p/get-recent-threads c uc nil)))
                "carol sees both threads, most recent activity first")
            (is (= [t1] (mapv :root-message-id (p/get-recent-threads c ua nil)))
                "starters are participants without replying")
            (is (= [t2] (mapv :root-message-id (p/get-recent-threads c ub nil))))
            ;; bump t1: it moves to the front for its participants
            (p/reply-in-thread! c ub room t1 "bump")
            (harness/wait-for-processing! c)
            (is (= [t1 t2] (mapv :root-message-id (p/get-recent-threads c uc nil)))
                "new activity reorders the view")
            (is (= [t1 t2] (mapv :root-message-id (p/get-recent-threads c ub nil)))
                "replying makes bob a participant of t1")
            (let [[e _] [(first (p/get-recent-threads c uc nil))]]
              (is (= 2 (:reply-count e)))
              (is (= room (:room-id e))))
            ;; cursor pagination over >20 threads
            (doseq [i (range 24)]
              (p/post-message! c ua room (str "extra" i)))
            (harness/wait-for-processing! c)
            (let [roots (mapv :message-id (page-all (fn [cur] (p/get-room-page c room cur))
                                                    :message-id))
                  extra-roots (->> roots (remove #{t1 t2}) (take 24))]
              (doseq [r extra-roots]
                (p/reply-in-thread! c uc room r "seed"))
              (harness/wait-for-processing! c)
              (let [page1 (p/get-recent-threads c uc nil)
                    page2 (p/get-recent-threads c uc (:last-activity-id (last page1)))
                    all (into page1 page2)]
                (is (= 20 (count page1)))
                (is (= 26 (count all)) "26 threads total for carol")
                (is (= [] (p/get-recent-threads c uc (:last-activity-id (last page2)))))))
            (is (= [] (p/get-recent-threads c 987654321 nil)))))

        (testing "unread counts: room messages only, O(1) marks, leave/rejoin"
          ;; room currently has 26 room messages (2 roots + 24 extra); replies excluded
          (is (= {room 26} (p/get-unread-counts c ub)))
          (is (= {room 26 other 0} (p/get-unread-counts c ua))
              "all member rooms covered, zero included")
          (p/mark-room-read! c ub room)
          (harness/wait-for-processing! c)
          (is (= {room 0} (p/get-unread-counts c ub)))
          (p/post-message! c ua room "after-mark")
          (harness/wait-for-processing! c)
          (is (= {room 1} (p/get-unread-counts c ub)))
          ;; leaving forgets the cursor; rejoining sees full history unread
          (p/leave-room! c ub room)
          (harness/wait-for-processing! c)
          (is (= {} (p/get-unread-counts c ub)))
          (p/join-room! c ub room)
          (harness/wait-for-processing! c)
          (is (= {room 27} (p/get-unread-counts c ub))
              "rejoin without a mark counts every room message ever"))

        (testing "mentions: parsing, resolution, rejection, pagination"
          (p/post-message! c ua room "hey @bob. and @carol and @bob and @missing and @")
          (harness/wait-for-processing! c)
          (is (= ["hey @bob. and @carol and @bob and @missing and @"]
                 (mapv :content (p/get-mentions-page c ub nil)))
              "period-terminated and duplicate tokens produce one mention")
          (is (= [room] (mapv :room-id (p/get-mentions-page c ub nil))))
          (is (= [ua] (mapv :user-id (p/get-mentions-page c ub nil)))
              "mention entries carry the sender")
          (is (= 1 (count (p/get-mentions-page c uc nil))))
          ;; comma does not terminate a token: "@bob," matches no handle
          (p/post-message! c ua room "hi @bob,")
          (harness/wait-for-processing! c)
          (is (= 1 (count (p/get-mentions-page c ub nil)))
              "token runs to whitespace/period only; '@bob,' resolves nothing")
          ;; mentions from thread replies; mentioned user need not be in the room
          (p/post-message! c ua other "root here")
          (harness/wait-for-processing! c)
          (let [root (:message-id (first (p/get-room-page c other nil)))]
            (p/reply-in-thread! c ua other root "ping @carol")
            (harness/wait-for-processing! c)
            (let [ms (p/get-mentions-page c uc nil)]
              (is (= ["ping @carol" ] (take 1 (mapv :content ms)))
                  "reply mentions delivered; carol is not in 'other'")
              (is (= [other] (take 1 (mapv :room-id ms))))))
          ;; rejected messages produce no mentions
          (p/post-message! c uc other "@bob smuggled") ;; carol not in 'other'
          (harness/wait-for-processing! c)
          (is (= 1 (count (p/get-mentions-page c ub nil))))
          ;; pagination
          (doseq [i (range 24)]
            (p/post-message! c ua room (str "spam @bob n" i)))
          (harness/wait-for-processing! c)
          (let [page1 (p/get-mentions-page c ub nil)
                page2 (p/get-mentions-page c ub (:message-id (last page1)))]
            (is (= 20 (count page1)))
            (is (= 25 (count (into page1 page2))))))))))

(defn test-module-concurrency-and-caps
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module (launch-args))
      (let [c (wrap-client ipc)
            ua (p/register! c "alice")
            ub (p/register! c "bob")
            room (p/create-room! c "main")]
        (doseq [u [ua ub]] (p/join-room! c u room))
        (harness/wait-for-processing! c)

        (testing "concurrent posts all land exactly once"
          (let [futs (mapv (fn [i] (future (p/post-message! c ua room (str "c" i))))
                           (range 30))]
            (doseq [f futs] (deref f 30000 :timeout))
            (harness/wait-for-processing! c)
            (let [contents (mapv :content (page-all (fn [cur] (p/get-room-page c room cur))
                                                    :message-id))]
              (is (= 30 (count contents)))
              (is (= (set (map #(str "c" %) (range 30))) (set contents)))
              (is (= {room 30} (p/get-unread-counts c ub))))))

        (testing "recent-threads keeps only the 200 most recently active"
          (doseq [i (range 205)]
            (p/post-message! c ua room (str "root" i)))
          (harness/wait-for-processing! c)
          (let [roots (->> (page-all (fn [cur] (p/get-room-page c room cur)) :message-id)
                           (filter #(re-matches #"root\d+" (:content %)))
                           (sort-by :content (fn [a b] (compare (parse-long (subs a 4))
                                                                (parse-long (subs b 4)))))
                           (mapv :message-id))]
            (is (= 205 (count roots)))
            ;; bob replies to all 205 threads oldest-root first
            (doseq [r roots]
              (p/reply-in-thread! c ub room r "hi"))
            (harness/wait-for-processing! c)
            (let [view (page-all (fn [cur] (p/get-recent-threads c ub cur))
                                 :last-activity-id)]
              (is (= 200 (count view)) "view trimmed to the 200 most recent")
              (is (= (set (drop 5 roots)) (set (mapv :root-message-id view)))
                  "the 5 least-recently-active threads fell off"))))))))
