(ns chat-app.performance-test-support
  "Performance and fault-tolerance private tests for chat-app. RocksDB
   operation counts are captured around the tough operations; bounds are
   set with generous headroom over any reasonable design while failing
   decisively the designs the spec forbids (per-member write fanout,
   per-heartbeat durable writes, history scans on paginated reads)."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [chat-app.protocol :as p]))

(defn- launch-args []
  (let [tasks (rand-nth [2 4])
        threads (+ 2 (rand-int (inc (- tasks 2))))]
    {:tasks tasks :threads threads}))

(defn capture-rocks-ops [f]
  (let [state (atom {})]
    (rtest/with-event-hook
      (fn [event-type data]
        (case event-type
          :rocks-read (swap! state update :rocks-read (fnil inc 0))
          :rocks-iterator (swap! state update :rocks-iterator (fnil inc 0))
          :rocks-iterator-read (swap! state update :rocks-iterator-read (fnil inc 0))
          :rocks-commit (swap! state update :rocks-writes (fnil #(+ % (:write-batch-count data)) 0))
          nil))
      (f)
      @state)))

(defn- total-reads [info]
  (+ (:rocks-read info 0) (:rocks-iterator-read info 0)))

(defn- page-all-room [c room]
  (loop [cursor nil acc []]
    (let [page (p/get-room-page c room cursor)]
      (if (empty? page)
        acc
        (recur (:message-id (last page)) (into acc page))))))

(defn test-module-write-volume
  "Durable write volume for the hot writes must be bounded independently
   of room size and heartbeat rate."
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module (launch-args))
      (let [c (wrap-client ipc)
            n-members 300
            users (mapv #(p/register! c (str "u" %)) (range n-members))
            room (p/create-room! c "bigroom")]
        (doseq [u users] (p/join-room! c u room))
        (harness/wait-for-processing! c)

        (testing "heartbeats perform no durable writes"
          (let [info (capture-rocks-ops
                      (fn []
                        (doseq [u (take 100 users)]
                          (p/heartbeat! c u))))]
            (is (< (:rocks-writes info 0) 10)
                (str "100 heartbeats must write nothing durable; " info))
            (is (= :online (p/get-presence c (first users))))))

        (testing "a post to a 300-member room writes a bounded number of records"
          (let [info (capture-rocks-ops
                      (fn []
                        (p/post-message! c (first users) room "hello room")
                        (harness/wait-for-processing! c)))]
            (is (< (:rocks-writes info 0) 40)
                (str "message processing must not write per room member; " info))
            ;; the write actually happened and is counted for everyone
            (is (= ["hello room"] (mapv :content (p/get-room-page c room nil))))
            (is (= {room 1} (p/get-unread-counts c (second users))))))

        (testing "mark-room-read is O(1), not a message scan"
          (doseq [i (range 50)]
            (p/post-message! c (first users) room (str "m" i)))
          (harness/wait-for-processing! c)
          (let [reader (second users)
                info (capture-rocks-ops
                      (fn []
                        (p/mark-room-read! c reader room)
                        (harness/wait-for-processing! c)))]
            (is (< (:rocks-writes info 0) 15)
                (str "mark-room-read writes a cursor, not per-message state; " info))
            (is (< (total-reads info) 60)
                (str "mark-room-read must not scan messages; " info))
            (is (= {room 0} (p/get-unread-counts c reader)))))))))

(defn test-module-read-costs
  "Paginated reads must cost page-work, not history-work: RocksDB reads
   during each tough read are bounded regardless of how much history
   exists beyond the page."
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module (launch-args))
      (let [c (wrap-client ipc)
            reader (p/register! c "reader")
            author (p/register! c "author")
            others (mapv #(p/register! c (str "o" %)) (range 30))
            deep (p/create-room! c "deep")]
        (p/set-profile! c author "Author" "T")
        (doseq [u (concat [reader author] others)] (p/join-room! c u deep))
        ;; joins are asynchronous; posts are membership-gated — barrier first
        (harness/wait-for-processing! c)
        ;; deep history: 250 room messages
        (doseq [i (range 250)]
          (p/post-message! c author deep (str "deep" i)))
        (harness/wait-for-processing! c)

        ;; NOTE: correctness is asserted OUTSIDE the captures. Under
        ;; rtest/with-event-hook, queries that emit rows from a reverse
        ;; range scan truncate to one row (hook instrumentation bug); the
        ;; scan's rocks costs are still counted, which is what these
        ;; bounds discriminate on.
        (testing "get-room-page cost is page-sized despite 250-message history"
          (is (= 20 (count (p/get-room-page c deep nil))))
          (let [info (capture-rocks-ops
                      (fn [] (p/get-room-page c deep nil)))]
            (is (< (total-reads info) 250)
                (str "room page must read ~page work, not the history; " info))))

        (testing "get-unread-counts costs O(rooms), not O(messages)"
          ;; reader joins 9 more rooms with 20 messages each
          (let [rooms (mapv #(p/create-room! c (str "r" %)) (range 9))]
            (doseq [r rooms]
              (p/join-room! c reader r)
              (p/join-room! c author r))
            (harness/wait-for-processing! c)
            (doseq [r rooms, i (range 20)]
              (p/post-message! c author r (str "x" i)))
            (harness/wait-for-processing! c)
            (let [counts (p/get-unread-counts c reader)]
              (is (= 10 (count counts)))
              (is (= 250 (get counts deep))))
            (let [info (capture-rocks-ops
                        (fn [] (p/get-unread-counts c reader)))]
              (is (< (total-reads info) 120)
                  (str "unread counts for 10 rooms must not scan the ~430 "
                       "messages behind them; " info)))))

        (testing "get-recent-threads cost is page-sized despite thread sizes"
          ;; author starts 25 threads; reader replies once to each; the
          ;; threads then grow to 10 replies each (250 replies total).
          (let [roots (->> (page-all-room c deep)
                           (take 25)
                           (mapv :message-id))]
            (doseq [r roots] (p/reply-in-thread! c reader deep r "in"))
            (harness/wait-for-processing! c)
            (doseq [r roots, i (range 9)]
              (p/reply-in-thread! c author deep r (str "grow" i)))
            (harness/wait-for-processing! c)
            (let [page (p/get-recent-threads c reader nil)]
              (is (= 20 (count page)))
              (is (every? #(= 10 (:reply-count %)) page)))
            (let [info (capture-rocks-ops
                        (fn [] (p/get-recent-threads c reader nil)))]
              (is (< (total-reads info) 200)
                  (str "recent-threads must read page entries + metadata, "
                       "not the 250 replies behind them; " info)))))

        (testing "get-mentions-page cost is page-sized despite mention history"
          (doseq [i (range 60)]
            (p/post-message! c author deep (str "ping @reader n" i)))
          (harness/wait-for-processing! c)
          (is (= 20 (count (p/get-mentions-page c reader nil))))
          (let [info (capture-rocks-ops
                      (fn [] (p/get-mentions-page c reader nil)))]
            (is (< (total-reads info) 250)
                (str "mentions page must not scan the 60-mention history; " info))))

        (testing "get-online-members cost is a bounded member scan"
          (doseq [u (concat [reader author] others)] (p/heartbeat! c u))
          (is (= 20 (count (p/get-online-members c deep nil))))
          (let [info (capture-rocks-ops
                      (fn [] (p/get-online-members c deep nil)))]
            (is (< (total-reads info) 400)
                (str "online members must not do unbounded per-member disk "
                     "work; " info))))))))

(defn test-module-fault-tolerance
  "A module update wipes in-memory state. Durable views must survive
   exactly; presence resets to offline until the next heartbeat."
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)]
    (with-open [ipc (rtest/create-ipc)]
      (rtest/launch-module! ipc module (launch-args))
      (let [c (wrap-client ipc)
            ua (p/register! c "alice")
            ub (p/register! c "bob")
            room (p/create-room! c "main")]
        (p/set-profile! c ua "Alice" "Eng")
        (doseq [u [ua ub]] (p/join-room! c u room))
        (harness/wait-for-processing! c)
        (p/post-message! c ua room "root @bob")
        (harness/wait-for-processing! c)
        (let [root (:message-id (first (p/get-room-page c room nil)))]
          (p/reply-in-thread! c ub room root "reply")
          (p/mark-room-read! c ub room)
          (p/heartbeat! c ua)
          (harness/wait-for-processing! c)
          (is (= :online (p/get-presence c ua)))

          ;; simulated restart: in-memory state lost, durable state survives
          (rtest/update-module! ipc module)

          (testing "durable state and derived views survive the update"
            (is (p/registered? c ua))
            (is (= ua (p/lookup-handle c "alice")))
            (is (= room (p/lookup-room c "main")))
            (is (= {:name "Alice" :title "Eng"} (p/get-profile c ua)))
            (is (= (vec (sort [ua ub])) (p/get-room-members c room nil)))
            (is (= ["root @bob"] (mapv :content (p/get-room-page c room nil))))
            (is (= 1 (:reply-count (first (p/get-room-page c room nil)))))
            (is (= ["reply"] (mapv :content (p/get-thread-page c root nil))))
            (is (= [root] (mapv :root-message-id (p/get-recent-threads c ub nil))))
            (is (= ["root @bob"] (mapv :content (p/get-mentions-page c ub nil))))
            (is (= {room 0} (p/get-unread-counts c ub))
                "read cursors are durable"))

          (testing "presence resets on restart; next heartbeat revives it"
            (is (= :offline (p/get-presence c ua))
                "in-memory presence is lost on update (permitted loosening)")
            (p/heartbeat! c ua)
            (is (= :online (p/get-presence c ua))))

          (testing "the module still processes writes after the update"
            (p/post-message! c ub room "post-update")
            (harness/wait-for-processing! c)
            (is (= "post-update" (:content (first (p/get-room-page c room nil)))))
            (is (= {room 2} (p/get-unread-counts c ua))
                "unread sequence continues correctly (ua never marked read)")
            (is (= {room 1} (p/get-unread-counts c ub))
                "ub's durable cursor still applies after the update")))))))
