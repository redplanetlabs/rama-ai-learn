(ns almost-equal.private-test-support
  "Shared test logic for the almost-equal.private-test-support namespace."
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  "Runs the contract tests against a solve function."
  [solve-fn]
  (testing "no plain Clojure functions defined in solution namespace"
    (harness/assert-no-clojure-fns 'almost-equal.solution))
  (let [cases [{:input "2 2\nuw\nun\n" :expected "Yes"}
             {:input "3 1\nj\nw\na\n" :expected "Yes"}
             {:input "4 1\nz\ni\nj\nb\n" :expected "Yes"}
             {:input "2 5\nwdqms\nppqfo\n" :expected "No"}
             {:input "3 4\nyzuz\nezuz\nuzuz\n" :expected "Yes"}
             {:input "5 3\nbag\nbad\nbat\nbed\ndad\n" :expected "No"}
             {:input "6 4\neeee\neeeg\ngeeg\nkeeg\nweee\nxeee\n" :expected "Yes"}
             {:input "6 4\ncccc\ncccd\ndccd\njccd\noccc\nwccc\n" :expected "Yes"}
             {:input "6 4\ncccc\ncccg\ngccg\nhccg\niccc\nuccc\n" :expected "Yes"}
             {:input "8 5\nlvntx\nlvytx\nlvynx\nmvfox\nmvyox\novfox\nlvnwx\nmvynx\n" :expected "Yes"}
             {:input "8 5\naoymb\nfoymb\npocmb\npvcdb\npicdb\npocdb\nfocmb\npicgb\n" :expected "Yes"}
             {:input "8 5\nbtacv\nbgacv\nbtlcv\nbwoyv\nbgucv\nbtoyv\nbguyv\nbwuyv\n" :expected "Yes"}]]
    (testing "solve"
      (doseq [{:keys [input expected]} cases]
        (testing (str "with input " (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str "expected " (pr-str expected))))))))
