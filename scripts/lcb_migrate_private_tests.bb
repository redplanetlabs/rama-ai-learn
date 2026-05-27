#!/usr/bin/env bb
;; Migrate existing LCB challenge scaffolds to support private tests.
;;
;; Usage:
;;   bb lcb-migrate-private-tests
;;   bb lcb-migrate-private-tests --challenge CHALLENGE_NAME
;;
;; This script:
;; 1) ensures LCB cache has decoded private tests
;; 2) adds :test-private alias to each challenge deps.edn
;; 3) generates test-private/{ns}/private_test_support.clj
;; 4) generates test-private/{ns}/private_challenge_test.clj
;; 5) updates README with private test command section (if missing)

(require '[babashka.fs :as fs]
         '[clojure.pprint :as pprint]
         '[clojure.string :as str])

;; lcb_fetch.bb and lcb_port.bb must be loaded first
;; (provides ensure-cache + helper generators)

(def ^:private project-root
  (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def ^:private lcb-headers
  #{"## Batch 6: LCB Easy"
    "## Batch 7: LCB Medium"
    "## Batch 8: LCB Hard"})

(def ^:private test-private-alias
  '{:extra-paths ["test-private"]
    :extra-deps {io.github.cognitect-labs/test-runner
                 {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
    :exec-fn cognitect.test-runner.api/test
    :exec-args {:dirs ["test-private"]}})

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
  (println "Usage: bb lcb-migrate-private-tests [--challenge CHALLENGE_NAME]")
  (println)
  (println "Migrates existing Batch 6/7/8 LCB challenges to include:")
  (println "- deps.edn :test-private alias")
  (println "- test-private/* private test namespaces")
  (println "- README test command section (if missing)"))

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

(defn- parse-title
  [readme-content]
  (some (fn [line]
          (when-let [[_ title] (re-matches #"#\s+(.+)" line)]
            title))
        (str/split-lines readme-content)))

(defn- parse-source
  [readme-content]
  (some (fn [line]
          (when-let [[_ platform contest-id date]
                     (re-matches #"\*\*Source:\*\*\s+([^\s]+)\s+—\s+([^\s]+)\s+\((\d{4}-\d{2}-\d{2})\)" line)]
            {:platform platform
             :contest-id contest-id
             :date date}))
        (str/split-lines readme-content)))

(defn- problem-for-challenge
  [challenge-name problems]
  (let [readme-path (str project-root "/challenges/" challenge-name "/README.md")
        readme      (slurp readme-path)
        title       (parse-title readme)
        source      (parse-source readme)
        by-title    (filter #(= title (:question_title %)) problems)
        matches     (if source
                      (filter (fn [p]
                                (and (= (:platform source) (:platform p))
                                     (= (:contest-id source) (:contest_id p))
                                     (str/starts-with? (:contest_date p) (:date source))))
                              by-title)
                      by-title)]
    (cond
      (= 1 (count matches))
      (first matches)

      (and (empty? matches) (= 1 (count by-title)))
      (first by-title)

      :else
      (throw (ex-info "Could not uniquely resolve LCB problem for challenge"
                      {:challenge challenge-name
                       :title title
                       :source source
                       :by-title-count (count by-title)
                       :match-count (count matches)})))))

(defn- update-deps-edn!
  [challenge-name]
  (let [path    (str project-root "/challenges/" challenge-name "/deps.edn")
        deps    (read-string (slurp path))
        updated (assoc-in deps [:aliases :test-private] test-private-alias)]
    (spit path (with-out-str (pprint/pprint updated)))
    (binding [*out* *err*]
      (println "  updated" path))))

(defn- ensure-readme-test-commands!
  [challenge-name]
  (let [path    (str project-root "/challenges/" challenge-name "/README.md")
        content (slurp path)]
    (if (str/includes? content "## Test Commands")
      (binding [*out* *err*]
        (println "  README already has Test Commands section"))
      (let [insert (str "## Test Commands\n\n"
                        "- Public challenge tests: `clojure -X:test`\n"
                        "- Private verification tests (post-challenge): `clojure -X:test-private`\n\n")
            updated (if (str/includes? content "## File Location")
                      (str/replace content "## File Location\n\n"
                                   (str insert "## File Location\n\n"))
                      (str (str/trimr content) "\n\n" insert))]
        (spit path updated)
        (binding [*out* *err*]
          (println "  updated" path))))))

(defn- write-private-tests!
  [challenge-name problem]
  (let [ns-name            challenge-name
        ns-path            (ns->path ns-name)
        private-support-ns (str ns-name ".private-test-support")
        test-type          (or (-> problem :public_test_cases first :testtype)
                               (-> problem :private_test_cases first :testtype)
                               "stdin")
        raw-func-name      (get-in problem [:metadata :func_name])
        fn-name            (if (= test-type "functional")
                             (camel->kebab (or raw-func-name "solve"))
                             "solve")
        private-cases      (if (= test-type "functional")
                             (private-functional-test-cases problem)
                             (private-stdin-test-cases problem))
        support-path       (str project-root "/challenges/" challenge-name
                                "/test-private/" ns-path "/private_test_support.clj")
        challenge-path     (str project-root "/challenges/" challenge-name
                                "/test-private/" ns-path "/private_challenge_test.clj")
        support-content    (if (= test-type "functional")
                             (gen-test-support-functional-for-cases private-support-ns ns-name fn-name private-cases)
                             (gen-test-support-stdin-for-cases private-support-ns ns-name fn-name private-cases))
        challenge-content  (gen-private-challenge-test ns-name fn-name)]
    (fs/create-dirs (fs/parent support-path))
    (spit support-path support-content)
    (spit challenge-path challenge-content)
    (binding [*out* *err*]
      (println "  wrote" support-path)
      (println "  wrote" challenge-path)
      (println "  private tests:" (count private-cases)))))

(defn- migrate-one!
  [challenge-name problems]
  (let [challenge-dir (str project-root "/challenges/" challenge-name)]
    (when-not (fs/exists? challenge-dir)
      (throw (ex-info "Challenge directory does not exist"
                      {:challenge challenge-name
                       :path challenge-dir})))
    (binding [*out* *err*]
      (println "Migrating" challenge-name "..."))
    (let [problem-meta (problem-for-challenge challenge-name problems)
          problem      (problem-with-private-test-cases problem-meta)]
      (update-deps-edn! challenge-name)
      (write-private-tests! challenge-name problem)
      (ensure-readme-test-commands! challenge-name)
      (binding [*out* *err*]
        (println "  done" challenge-name)))))

(defn -main
  [args]
  (let [{:keys [challenge help]} (parse-args args)]
    (when help
      (usage)
      (System/exit 0))
    (let [all-challenges (lcb-challenges-from-order)
          selected       (if challenge
                           [challenge]
                           all-challenges)
          problems       (ensure-cache)]
      (binding [*out* *err*]
        (println "LCB challenges to migrate:" (count selected)))
      (doseq [c selected]
        (migrate-one! c problems))
      (println "Migration complete for" (count selected) "challenge(s)."))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
