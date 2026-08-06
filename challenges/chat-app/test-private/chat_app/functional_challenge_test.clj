(ns chat-app.functional-challenge-test
  "Runs functional private tests against the agent implementation."
  (:require
   [clojure.test :refer [deftest testing]]
   [chat-app.functional-test-support :as support]))

(deftest identity-and-rooms-challenge-test
  (testing "chat-app: registration, handles, rooms, profiles"
    (support/test-module-identity-and-rooms
     (requiring-resolve 'chat-app.module/create-module))))

(deftest membership-and-presence-challenge-test
  (testing "chat-app: membership, presence, online members"
    (support/test-module-membership-and-presence
     (requiring-resolve 'chat-app.module/create-module))))

(deftest messaging-challenge-test
  (testing "chat-app: messages, pages, threads"
    (support/test-module-messaging
     (requiring-resolve 'chat-app.module/create-module))))

(deftest recent-threads-unread-mentions-challenge-test
  (testing "chat-app: recent threads, unread counts, mentions"
    (support/test-module-recent-threads-unread-mentions
     (requiring-resolve 'chat-app.module/create-module))))

(deftest concurrency-and-caps-challenge-test
  (testing "chat-app: concurrent writes and view caps"
    (support/test-module-concurrency-and-caps
     (requiring-resolve 'chat-app.module/create-module))))
