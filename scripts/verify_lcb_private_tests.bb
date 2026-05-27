#!/usr/bin/env bb
;; Verify migrated LCB challenges include private test support.
;;
;; Usage:
;;   bb verify-lcb-private-tests
;;   bb verify-lcb-private-tests --challenge CHALLENGE_NAME

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def ^:private project-root
  (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def ^:private lcb-headers
  #{"## Batch 6: LCB Easy"
    "## Batch 7: LCB Medium"
    "## Batch 8: LCB Hard"})

(defn- parse-args
  [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [[flag val & rest] args]
        (case flag
          "--challenge" (recur rest (assoc opts :challenge val))
          "--help"      (recur (if (nil? val) rest (cons val rest))
                                 (assoc opts :help true))
          (do (binding [*out* *err*]
                (println "Unknown flag:" flag))
              (recur (if (nil? val) rest (cons val rest)) opts)))))))

(defn- usage []
  (println "Usage: bb verify-lcb-private-tests [--challenge CHALLENGE_NAME]")
  (println)
  (println "Checks that each LCB challenge has:")
  (println "- deps.edn :test-private alias")
  (println "- test-private/<ns>/private_test_support.clj")
  (println "- test-private/<ns>/private_challenge_test.clj")
  (println "- README private test command (clojure -X:test-private)"))

(defn- lcb-challenges-from-order
  []
  (let [path  (str project-root "/CHALLENGE_ORDER.md")
        lines (str/split-lines (slurp path))]
    (loop [xs lines
           in-lcb? false
           acc []]
      (if-let [line (first xs)]
        (let [trimmed (str/trim line)]
          (cond
            (str/starts-with? trimmed "## ")
            (recur (rest xs) (contains? lcb-headers trimmed) acc)

            (and in-lcb? (str/starts-with? trimmed "- "))
            (recur (rest xs) in-lcb? (conj acc (subs trimmed 2)))

            :else
            (recur (rest xs) in-lcb? acc)))
        acc))))

(defn- ns-path
  [challenge-name]
  (str/replace challenge-name "-" "_"))

(defn- deps-errors
  [challenge-name]
  (let [path (str project-root "/challenges/" challenge-name "/deps.edn")]
    (cond
      (not (fs/exists? path))
      [(str "missing deps.edn: " path)]

      :else
      (let [deps   (read-string (slurp path))
            alias  (get-in deps [:aliases :test-private])
            paths  (set (:extra-paths alias))
            dirs   (set (get-in alias [:exec-args :dirs]))]
        (cond-> []
          (not (map? alias))
          (conj "missing :aliases :test-private")

          (and (map? alias) (not (contains? paths "test-private")))
          (conj ":test-private missing :extra-paths [\"test-private\"]")

          (and (map? alias) (not (contains? dirs "test-private")))
          (conj ":test-private missing :exec-args {:dirs [\"test-private\"]}"))))))

(defn- file-errors
  [challenge-name]
  (let [base (str project-root "/challenges/" challenge-name)
        np   (ns-path challenge-name)
        support-path   (str base "/test-private/" np "/private_test_support.clj")
        challenge-path (str base "/test-private/" np "/private_challenge_test.clj")]
    (cond-> []
      (not (fs/exists? support-path))
      (conj (str "missing private support file: " support-path))

      (not (fs/exists? challenge-path))
      (conj (str "missing private challenge test file: " challenge-path)))))

(defn- readme-errors
  [challenge-name]
  (let [path (str project-root "/challenges/" challenge-name "/README.md")]
    (cond
      (not (fs/exists? path))
      [(str "missing README.md: " path)]

      :else
      (let [content (slurp path)]
        (cond-> []
          (not (str/includes? content "clojure -X:test-private"))
          (conj "README missing private test command: clojure -X:test-private"))))))

(defn- verify-one
  [challenge-name]
  (let [challenge-dir (str project-root "/challenges/" challenge-name)]
    (if-not (fs/exists? challenge-dir)
      [(str "missing challenge directory: " challenge-dir)]
      (vec (concat (deps-errors challenge-name)
                   (file-errors challenge-name)
                   (readme-errors challenge-name))))))

(defn -main
  [args]
  (let [{:keys [challenge help]} (parse-args args)]
    (when help
      (usage)
      (System/exit 0))
    (let [targets (if challenge [challenge] (lcb-challenges-from-order))
          _       (println "Verifying LCB private-test migration for" (count targets) "challenge(s)...")
          results (mapv (fn [c] [c (verify-one c)]) targets)
          failed  (filterv (fn [[_ errs]] (seq errs)) results)]
      (doseq [[c errs] results]
        (if (seq errs)
          (do
            (println "FAIL" c)
            (doseq [e errs]
              (println "  -" e)))
          (println "PASS" c)))
      (println)
      (println "Summary: pass" (- (count results) (count failed))
               "fail" (count failed)
               "total" (count results))
      (if (seq failed)
        (System/exit 1)
        (println "verify-lcb-private-tests: OK")))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
