#!/usr/bin/env bb
;; List and filter cached LiveCodeBench problems.
;;
;; Usage: bb lcb-list [--difficulty easy|medium|hard]
;;                    [--platform leetcode|codeforces|atcoder]
;;                    [--type functional|stdin]
;;                    [--after YYYY-MM-DD] [--before YYYY-MM-DD]
;;                    [--limit N]
;;                    [--id QUESTION_ID]
;;                    [--search TEXT]

(require '[clojure.string :as str])

;; lcb_fetch.bb must be loaded first (provides ensure-cache)

(defn- test-type
  "Returns the test type of the first public test case."
  [problem]
  (-> problem :public_test_cases first :testtype))

(defn- matches-filter?
  "Returns true if a problem matches all specified filters."
  [problem {:keys [difficulty platform type after before id search]}]
  (and (or (nil? difficulty) (= difficulty (:difficulty problem)))
       (or (nil? platform) (= platform (:platform problem)))
       (or (nil? type) (= type (test-type problem)))
       (or (nil? after) (>= (compare (:contest_date problem) after) 0))
       (or (nil? before) (<= (compare (:contest_date problem) before) 0))
       (or (nil? id) (= id (:question_id problem)))
       (or (nil? search)
           (str/includes? (str/lower-case (:question_title problem))
                          (str/lower-case search)))))

(defn filter-problems
  "Filters and sorts problems by the given options."
  [problems opts]
  (let [limit (or (:limit opts) Long/MAX_VALUE)]
    (->> problems
         (filter #(matches-filter? % opts))
         (sort-by :contest_date)
         (take limit)
         vec)))

(defn- format-row
  "Formats a problem as a concise one-line summary."
  [problem]
  (let [date (subs (:contest_date problem) 0 10)]
    (format "%-12s %-8s %-10s %-6s %s"
            (:question_id problem)
            (:platform problem)
            date
            (:difficulty problem)
            (:question_title problem))))

(defn- parse-args
  "Parses command-line arguments into an options map."
  [args]
  (loop [args args, opts {}]
    (if (empty? args)
      opts
      (let [[flag val & rest] args]
        (case flag
          "--difficulty" (recur rest (assoc opts :difficulty val))
          "--platform"   (recur rest (assoc opts :platform val))
          "--type"       (recur rest (assoc opts :type val))
          "--after"      (recur rest (assoc opts :after val))
          "--before"     (recur rest (assoc opts :before val))
          "--limit"      (recur rest (assoc opts :limit (parse-long val)))
          "--id"         (recur rest (assoc opts :id val))
          "--search"     (recur rest (assoc opts :search val))
          "--edn"        (recur (cons val rest) (assoc opts :edn true))
          (do (binding [*out* *err*]
                (println "Unknown flag:" flag))
              (recur (cons val rest) opts)))))))

(defn -main [args]
  (let [opts     (parse-args args)
        problems (ensure-cache)
        filtered (filter-problems problems opts)]
    (if (:edn opts)
      (prn filtered)
      (do
        (println (format "%-12s %-8s %-10s %-6s %s"
                         "ID" "PLATFORM" "DATE" "DIFF" "TITLE"))
        (println (apply str (repeat 80 "-")))
        (doseq [p filtered]
          (println (format-row p)))
        (println)
        (println (count filtered) "problems")))))
