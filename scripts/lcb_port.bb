#!/usr/bin/env bb
;; Scaffold a Rama challenge from a LiveCodeBench problem.
;;
;; Usage: bb lcb-port --id QUESTION_ID [--name CHALLENGE_NAME]
;;
;; Generates challenge files using a deframafn pattern (no module/protocol).
;; The agent implements a single deframafn that solves the problem.
;;
;; Public tests are generated under src/ + test/.
;; Private tests are generated under test-private/ and run via -X:test-private.

(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.walk :as walk])

;; lcb_fetch.bb must be loaded first (provides ensure-cache)

(def ^:private project-root
  (str (fs/parent (fs/parent (fs/absolutize *file*)))))

;;; Naming helpers

(defn- title->challenge-name
  "Converts a problem title to a kebab-case challenge name."
  [title]
  (-> title
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-|-$" "")))

(defn- camel->kebab
  "Converts camelCase to kebab-case."
  [s]
  (-> s
      (str/replace #"([a-z])([A-Z])" "$1-$2")
      str/lower-case))

(defn- python-name->rama-arg
  "Converts a Python parameter name like 'nums' to '*nums'."
  [s]
  (str "*" (camel->kebab (str/trim s))))

(defn- parse-python-params
  "Extracts parameter names from Python starter code.
   Returns vector of rama-style arg names like ['*nums' '*target']."
  [starter-code]
  (when-let [[_ params] (re-find #"self,\s*(.+?)\)" (or starter-code ""))]
    (mapv (fn [p]
            (let [name (first (str/split (str/trim p) #"[:\s]"))]
              (python-name->rama-arg name)))
          (str/split params #","))))

(defn- infer-arg-count
  "Infers the number of arguments from test cases."
  [problem]
  (let [tc (first (:public_test_cases problem))]
    (count (str/split-lines (:input tc)))))

(defn- ns->path
  "Converts a namespace to a file path segment."
  [ns-str]
  (str/replace ns-str "-" "_"))

;;; Test case parsing

(defn- json->clj-str
  "Parses a JSON value and returns its Clojure pr-str representation.
   Ensures vectors (not lists) for JSON arrays."
  [s]
  (let [parsed (json/parse-string s)]
    (pr-str (walk/postwalk
             (fn [x] (if (sequential? x) (vec x) x))
             parsed))))

(defn- parse-functional-args
  "Parses functional test input into a vector of Clojure arg strings.
   Input format: one JSON value per line, one per function argument."
  [input-str]
  (mapv json->clj-str (str/split-lines input-str)))

(defn- functional-test-cases-from
  "Extracts functional test cases as [{:args [clj-str ...] :expected clj-str}]
   from a sequence of raw test-case maps."
  [raw-cases]
  (into []
        (map (fn [tc]
               {:args (parse-functional-args (:input tc))
                :expected (json->clj-str (:output tc))}))
        raw-cases))

(defn- functional-test-cases
  [problem]
  (functional-test-cases-from (:public_test_cases problem)))

(defn- private-functional-test-cases
  [problem]
  (functional-test-cases-from (:private_test_cases problem)))

(defn- stdin-test-cases-from
  "Extracts stdin test cases as [{:input :expected}] from raw test-case maps."
  [raw-cases]
  (into []
        (map (fn [tc]
               {:input (pr-str (:input tc))
                :expected (pr-str (str/trimr (:output tc)))}))
        raw-cases))

(defn- stdin-test-cases
  [problem]
  (stdin-test-cases-from (:public_test_cases problem)))

(defn- private-stdin-test-cases
  [problem]
  (stdin-test-cases-from (:private_test_cases problem)))

;;; File generation

(defn- gen-deps-edn
  "Generates deps.edn content for a challenge."
  [challenge-name]
  (str "{:mvn/repos {\"nexus-releases\" {:url \"https://nexus.redplanetlabs.com/repository/maven-public-releases\"}}
 :deps {com.rpl/rama            {:mvn/version \"1.8.0\"}
        org.clojure/clojure     {:mvn/version \"1.12.4\"}
        rama-challenges/harness {:local/root \"../../lib/harness\"}}
 :paths [\"src\"
         \"test\"
         \"../../implementations/" challenge-name "/src\"]
 :aliases {:test {:extra-deps {io.github.cognitect-labs/test-runner
                               {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}
                  :exec-fn cognitect.test-runner.api/test
                  :exec-args {:dirs [\"test\"]}}
           :test-private {:extra-paths [\"test-private\"]
                          :extra-deps {io.github.cognitect-labs/test-runner
                                       {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}
                          :exec-fn cognitect.test-runner.api/test
                          :exec-args {:dirs [\"test-private\"]}}
           :test-harness {:extra-paths [\"src\" \"test-harness\" \"test-resources\"]
                          :extra-deps {io.github.cognitect-labs/test-runner
                                       {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}
                          :exec-fn cognitect.test-runner.api/test
                          :exec-args {:dirs [\"test-harness\"]}}
           :nrepl {:extra-deps {nrepl/nrepl {:mvn/version \"1.3.0\"}}
                   :main-opts [\"-m\" \"nrepl.cmdline\"]}}}
"))

(defn- strip-html
  "Strips HTML tags and decodes common entities."
  [html]
  (-> html
      (str/replace #"<[^>]+>" "")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&amp;" "&")
      (str/replace "&nbsp;" " ")
      (str/replace "\\$" "")
      (str/replace "\\texttt{" "`")
      (str/replace "}" "`")
      str/trim))

(defn- gen-readme
  "Generates README.md for a challenge."
  [problem challenge-name ns-name test-type fn-name]
  (let [content (strip-html (:question_content problem))]
    (str "# " (:question_title problem) "\n\n"
         "**Source:** " (:platform problem) " — " (:contest_id problem)
         " (" (subs (:contest_date problem) 0 10) ")\n"
         "**Difficulty:** " (:difficulty problem) "\n\n"
         "## Problem\n\n"
         content "\n\n"
         "## Contract\n\n"
         "Implement a `deframafn` named `" fn-name "` in namespace `" ns-name ".solution`.\n\n"
         (if (= test-type "functional")
           (str "The function receives the problem arguments and returns the result.\n"
                "Tests call it directly: `(" fn-name " arg1 arg2 ...)`\n")
           (str "The function receives a single input string (stdin format) and returns\n"
                "the output string.\n"
                "Tests call it directly: `(" fn-name " input-string)`\n"))
         "\n### Constraint\n\n"
         "All computation must use Rama dataflow code. You may call existing\n"
         "Clojure functions (e.g. `conj`, `count`, `inc`) but must not define any\n"
         "local functions (`defn`, `fn`, `#()`).  This constraint is specific to\n"
         "this challenge, and overrides the more general advice given in any\n"
         "skills.\n\n"
         "## Test Commands\n\n"
         "- Public challenge tests: `clojure -X:test`\n"
         "- Private verification tests (post-challenge): `clojure -X:test-private`\n\n"
         "## nREPL\n\n"
         "Start an nREPL with the test classpath:\n\n"
         "```bash\nclj -M:nrepl\n```\n\n"
         "## File Location\n\n"
         "Write your solution to:\n"
         "```\nimplementations/" challenge-name "/src/" (ns->path ns-name) "/solution.clj\n```\n")))

(defn- gen-call-expr
  "Generates a call expression string for a test case.
   E.g. (solve-fn [1 2 3] 9)"
  [args]
  (str "(solve-fn " (str/join " " args) ")"))

(defn- gen-test-support-functional
  "Generates test_support.clj for a functional-type problem."
  [ns-name fn-name problem]
  (let [cases (functional-test-cases problem)
        test-forms (str/join "\n\n      "
                     (map-indexed
                      (fn [i {:keys [args expected]}]
                        (str "(testing \"case " i "\"\n"
                             "        (is (= " expected " " (gen-call-expr args) ")))"))
                      cases))]
    (str "(ns " ns-name ".test-support
  \"Shared test logic for the " ns-name " challenge.\"
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  \"Runs the contract tests against a solve function.\"
  [solve-fn]
  (testing \"no plain Clojure functions defined in solution namespace\"
    (harness/assert-no-clojure-fns '" ns-name ".solution))
  (testing \"" fn-name "\"
      " test-forms "))
")))

(defn- gen-test-support-stdin
  "Generates test_support.clj for a stdin-type problem."
  [ns-name fn-name problem]
  (let [cases (stdin-test-cases problem)
        case-strs (str/join "\n             "
                            (map (fn [{:keys [input expected]}]
                                   (str "{:input " input
                                        " :expected " expected "}"))
                                 cases))]
    (str "(ns " ns-name ".test-support
  \"Shared test logic for the " ns-name " challenge.\"
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  \"Runs the contract tests against a solve function.\"
  [solve-fn]
  (testing \"no plain Clojure functions defined in solution namespace\"
    (harness/assert-no-clojure-fns '" ns-name ".solution))
  (let [cases [" case-strs "]]
    (testing \"" fn-name "\"
      (doseq [{:keys [input expected]} cases]
        (testing (str \"with input \" (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str \"expected \" (pr-str expected))))))))
")))

(defn- gen-test-support-functional-for-cases
  "Generates test support namespace content for functional test cases."
  [support-ns solution-ns fn-name cases]
  (let [test-forms (str/join "\n\n      "
                             (map-indexed
                              (fn [i {:keys [args expected]}]
                                (str "(testing \"case " i "\"\n"
                                     "        (is (= " expected " " (gen-call-expr args) ")))"))
                              cases))]
    (str "(ns " support-ns "
  \"Shared test logic for the " support-ns " namespace.\"
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  \"Runs the contract tests against a solve function.\"
  [solve-fn]
  (testing \"no plain Clojure functions defined in solution namespace\"
    (harness/assert-no-clojure-fns '" solution-ns ".solution))
  (testing \"" fn-name "\"
      " test-forms "))
")))

(defn- gen-test-support-stdin-for-cases
  "Generates test support namespace content for stdin test cases."
  [support-ns solution-ns fn-name cases]
  (let [case-strs (str/join "\n             "
                            (map (fn [{:keys [input expected]}]
                                   (str "{:input " input
                                        " :expected " expected "}"))
                                 cases))]
    (str "(ns " support-ns "
  \"Shared test logic for the " support-ns " namespace.\"
  (:require
   [clojure.test :refer [is testing]]
   [rama-challenges.harness :as harness]))

(defn test-solution
  \"Runs the contract tests against a solve function.\"
  [solve-fn]
  (testing \"no plain Clojure functions defined in solution namespace\"
    (harness/assert-no-clojure-fns '" solution-ns ".solution))
  (let [cases [" case-strs "]]
    (testing \"" fn-name "\"
      (doseq [{:keys [input expected]} cases]
        (testing (str \"with input \" (pr-str (subs input 0 (min 40 (count input)))))
          (is (= expected (solve-fn input))
              (str \"expected \" (pr-str expected))))))))
")))

(defn- gen-challenge-test
  "Generates challenge_test.clj content."
  [ns-name fn-name]
  (str "(ns " ns-name ".challenge-test
  \"Verifies the agent's deframafn implementation.\"
  (:require
   [clojure.test :refer [deftest testing]]
   [" ns-name ".test-support :as support]))

;; Tests verify the agent's " fn-name " deframafn.

(deftest challenge-test
  (testing \"" fn-name " (agent implementation)\"
    (support/test-solution
     (requiring-resolve '" ns-name ".solution/" fn-name "))))
"))

(defn- gen-private-challenge-test
  "Generates private_challenge_test.clj content."
  [ns-name fn-name]
  (str "(ns " ns-name ".private-challenge-test
  \"Runs hidden/private tests against the agent implementation.\"
  (:require
   [clojure.test :refer [deftest testing]]
   [" ns-name ".private-test-support :as support]))

(deftest private-challenge-test
  (testing \"" fn-name " (agent implementation, private tests)\"
    (support/test-solution
     (requiring-resolve '" ns-name ".solution/" fn-name "))))
"))

(defn- gen-harness-test
  "Generates harness_test.clj content."
  [ns-name fn-name]
  (str "(ns " ns-name ".harness-test
  \"Validates the test harness against the reference implementation.\"
  (:require
   [clojure.test :refer [deftest testing]]
   [" ns-name ".test-support :as support]
   [" ns-name ".solution :as ref]))

;; Validates the test harness using the reference implementation.

(deftest harness-detects-success-test
  (testing \"harness-detects-success\"
    (testing \"" fn-name " (reference implementation)\"
      (support/test-solution ref/" fn-name "))))
"))

(defn- gen-reference-solution-functional
  "Generates the reference solution.clj for a functional-type problem."
  [ns-name fn-name problem]
  (let [params (or (parse-python-params (:starter_code problem))
                   (mapv #(str "*arg" %) (range (infer-arg-count problem))))
        arg-list (str/join " " params)]
    (str "(ns " ns-name ".solution
  \"Reference implementation for " ns-name " challenge.\"
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

;; TODO: implement the algorithm
(deframafn " fn-name " [" arg-list "]
  (throw (ex-info \"Not implemented\" {})))
")))

(defn- gen-reference-solution-stdin
  "Generates the reference solution.clj for a stdin-type problem."
  [ns-name fn-name problem]
  (str "(ns " ns-name ".solution
  \"Reference implementation for " ns-name " challenge.\"
  (:require [com.rpl.rama :refer :all]
            [com.rpl.rama.path :refer :all]))

;; TODO: implement the algorithm
;; Receives the full stdin input as a single string.
;; Must return the expected output as a string.
(deframafn " fn-name " [*input]
  (throw (ex-info \"Not implemented\" {})))
"))

;;; CHALLENGE_ORDER.md maintenance

(def ^:private section-headers
  {"easy"   "## Batch 6: LCB Easy"
   "medium" "## Batch 7: LCB Medium"
   "hard"   "## Batch 8: LCB Hard"})

(defn- update-challenge-order!
  "Adds the challenge to the appropriate LCB section in CHALLENGE_ORDER.md."
  [challenge-name difficulty]
  (let [path    (str project-root "/CHALLENGE_ORDER.md")
        content (slurp path)
        header  (get section-headers difficulty)
        entry   (str "- " challenge-name)]
    (when header
      (if (str/includes? content entry)
        (binding [*out* *err*]
          (println "  CHALLENGE_ORDER.md already contains" challenge-name))
        (let [;; Ensure sections exist
              content (reduce
                       (fn [c [_ h]]
                         (if (str/includes? c h)
                           c
                           (str (str/trimr c) "\n\n" h "\n")))
                       content
                       (sort-by key section-headers))
              ;; Find the header and insert after existing entries
              lines   (str/split-lines content)
              idx     (.indexOf lines header)
              ;; Find insertion point: after header and existing entries
              insert  (loop [i (inc idx)]
                        (if (and (< i (count lines))
                                 (str/starts-with? (nth lines i) "- "))
                          (recur (inc i))
                          i))
              updated (str (str/join "\n" (concat (take insert lines)
                                                  [entry]
                                                  (drop insert lines)))
                           "\n")]
          (spit path updated)
          (binding [*out* *err*]
            (println "  updated CHALLENGE_ORDER.md:" header)))))))

;;; Scaffolding orchestration

(defn- write-file!
  "Writes content to path, creating parent directories as needed."
  [path content]
  (fs/create-dirs (fs/parent path))
  (spit (str path) content)
  (binding [*out* *err*]
    (println "  wrote" (str path))))

(defn scaffold-challenge!
  "Generates all files for a challenge from a LiveCodeBench problem."
  [problem {:keys [challenge-name]}]
  (let [challenge-name     (or challenge-name
                                (title->challenge-name (:question_title problem)))
        ns-name            challenge-name
        ns-path            (ns->path ns-name)
        public-support-ns  (str ns-name ".test-support")
        private-support-ns (str ns-name ".private-test-support")
        test-type          (or (-> problem :public_test_cases first :testtype)
                               (-> problem :private_test_cases first :testtype)
                               "stdin")
        raw-func-name      (get-in problem [:metadata :func_name])
        fn-name            (if (= test-type "functional")
                             (camel->kebab (or raw-func-name "solve"))
                             "solve")
        public-cases       (if (= test-type "functional")
                             (functional-test-cases problem)
                             (stdin-test-cases problem))
        private-cases      (if (= test-type "functional")
                             (private-functional-test-cases problem)
                             (private-stdin-test-cases problem))
        base               (str project-root "/challenges/" challenge-name)]
    (when (fs/exists? base)
      (binding [*out* *err*]
        (println "WARNING: challenge directory already exists:" base)))
    (binding [*out* *err*]
      (println "Scaffolding challenge:" challenge-name)
      (println "  test-type:" test-type)
      (println "  deframafn:" fn-name)
      (println "  public tests:" (count public-cases))
      (println "  private tests:" (count private-cases)))

    ;; deps.edn
    (write-file! (str base "/deps.edn")
                 (gen-deps-edn challenge-name))

    ;; README.md
    (write-file! (str base "/README.md")
                 (gen-readme problem challenge-name ns-name test-type fn-name))

    ;; Public test_support.clj
    (write-file! (str base "/src/" ns-path "/test_support.clj")
                 (if (= test-type "functional")
                   (gen-test-support-functional-for-cases public-support-ns ns-name fn-name public-cases)
                   (gen-test-support-stdin-for-cases public-support-ns ns-name fn-name public-cases)))

    ;; Private test support + test namespace (kept outside default test alias)
    (write-file! (str base "/test-private/" ns-path "/private_test_support.clj")
                 (if (= test-type "functional")
                   (gen-test-support-functional-for-cases private-support-ns ns-name fn-name private-cases)
                   (gen-test-support-stdin-for-cases private-support-ns ns-name fn-name private-cases)))

    (write-file! (str base "/test-private/" ns-path "/private_challenge_test.clj")
                 (gen-private-challenge-test ns-name fn-name))

    ;; challenge_test.clj
    (write-file! (str base "/test/" ns-path "/challenge_test.clj")
                 (gen-challenge-test ns-name fn-name))

    ;; harness_test.clj
    (write-file! (str base "/test-harness/" ns-path "/harness_test.clj")
                 (gen-harness-test ns-name fn-name))

    ;; reference solution.clj
    (write-file! (str base "/test-resources/" ns-path "/solution.clj")
                 (if (= test-type "functional")
                   (gen-reference-solution-functional ns-name fn-name problem)
                   (gen-reference-solution-stdin ns-name fn-name problem)))

    ;; agent implementation directory (empty)
    (let [impl-dir (str project-root "/implementations/" challenge-name "/src/" ns-path)]
      (fs/create-dirs impl-dir)
      (binding [*out* *err*]
        (println "  created" impl-dir)))

    ;; Update CHALLENGE_ORDER.md
    (update-challenge-order! challenge-name (:difficulty problem))

    (binding [*out* *err*]
      (println "Done. Challenge scaffolded at" base))))

(defn- parse-args [args]
  (loop [args args, opts {}]
    (if (empty? args)
      opts
      (let [[flag val & rest] args]
        (case flag
          "--id"   (recur rest (assoc opts :id val))
          "--name" (recur rest (assoc opts :challenge-name val))
          (do (binding [*out* *err*]
                (println "Unknown flag:" flag))
              (recur (cons val rest) opts)))))))

(defn -main [args]
  (let [opts    (parse-args args)
        _       (when-not (:id opts)
                  (println "Usage: bb lcb-port --id QUESTION_ID [--name CHALLENGE_NAME]")
                  (System/exit 1))
        problem (ensure-problem-by-id (:id opts))]
    (if problem
      (scaffold-challenge! problem opts)
      (do (println "Problem not found:" (:id opts))
          (System/exit 1)))))
