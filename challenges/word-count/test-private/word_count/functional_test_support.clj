(ns word-count.functional-test-support
  "Functional private tests for word-count. These exercise the public
   protocol only and make no assumption about the topology type or
   partitioning strategy, so any correct implementation passes."
  (:require
   [clojure.test :refer [is testing]]
   [com.rpl.rama.test :as rtest]
   [rama-challenges.harness :as harness]
   [word-count.protocol :as p]))

(defn test-module-functional
  [create-module-fn]
  (let [{:keys [module wrap-client]} (create-module-fn)
        tasks   (rand-nth [2 4])
        threads (+ 2 (rand-int (inc (- tasks 2))))]
    (binding [harness/*task-count* tasks]
      (with-open [ipc (rtest/create-ipc)]
        (rtest/launch-module! ipc module {:tasks tasks :threads threads})
        (let [client (wrap-client ipc)]

          (testing "a word that has never been seen counts 0"
            (is (= 0 (p/get-count client "aardvark"))))

          (testing "words of one sentence are each counted"
            (p/add-sentence! client "the quick brown fox")
            (harness/wait-for-processing! client)
            (doseq [w ["the" "quick" "brown" "fox"]]
              (is (= 1 (p/get-count client w)) (str "count of " w)))
            (is (= 0 (p/get-count client "dog"))
                "a word absent from every sentence still counts 0"))

          (testing "counts accumulate across sentences"
            (p/add-sentence! client "the lazy dog")
            (harness/wait-for-processing! client)
            (is (= 2 (p/get-count client "the")) "the appeared in two sentences")
            (is (= 1 (p/get-count client "lazy")))
            (is (= 1 (p/get-count client "dog")))
            (is (= 1 (p/get-count client "fox"))
                "words from the earlier sentence are unchanged"))

          (testing "a word repeated within one sentence counts once per occurrence"
            (p/add-sentence! client "buffalo buffalo buffalo")
            (harness/wait-for-processing! client)
            (is (= 3 (p/get-count client "buffalo"))))

          (testing "matching ignores case"
            (p/add-sentence! client "Dog DOG dOg")
            (harness/wait-for-processing! client)
            (is (= 4 (p/get-count client "dog"))
                "three new occurrences on top of the earlier lowercase one")
            (is (= 4 (p/get-count client "DOG"))
                "querying with a different case gives the same total")
            (is (= 4 (p/get-count client "Dog"))))

          (testing "punctuation is part of the word"
            (p/add-sentence! client "fox. fox")
            (harness/wait-for-processing! client)
            (is (= 1 (p/get-count client "fox."))
                "fox. is a distinct word from fox")
            (is (= 2 (p/get-count client "fox"))
                "the bare fox picked up exactly one new occurrence"))

          (testing "runs of whitespace separate words like a single space"
            (p/add-sentence! client "  alpha   beta\tgamma\ndelta  ")
            (harness/wait-for-processing! client)
            (doseq [w ["alpha" "beta" "gamma" "delta"]]
              (is (= 1 (p/get-count client w)) (str "count of " w))))

          (testing "a sentence with no words records nothing"
            (let [before (p/get-count client "the")]
              (p/add-sentence! client "")
              (p/add-sentence! client "   ")
              (p/add-sentence! client "\t\n")
              (harness/wait-for-processing! client)
              (is (= before (p/get-count client "the"))
                  "empty and whitespace-only sentences leave counts untouched")
              (is (= 0 (p/get-count client ""))
                  "the empty string is not a word")))

          (testing "many sentences accumulate exactly"
            (dotimes [_ 50]
              (p/add-sentence! client "repeat me repeat"))
            (harness/wait-for-processing! client)
            (is (= 100 (p/get-count client "repeat"))
                "two occurrences per sentence across 50 sentences")
            (is (= 50 (p/get-count client "me"))))

          (testing "a large vocabulary spread across tasks stays exact"
            (doseq [i (range 200)]
              (p/add-sentence! client (str "w" i " common")))
            (harness/wait-for-processing! client)
            (is (= 200 (p/get-count client "common"))
                "the common word saw every sentence")
            (doseq [i [0 37 199]]
              (is (= 1 (p/get-count client (str "w" i)))
                  (str "distinct word w" i)))
            (is (= 0 (p/get-count client "w200"))
                "a word just outside the range was never added")))))))
