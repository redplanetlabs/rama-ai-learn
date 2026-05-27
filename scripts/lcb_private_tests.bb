#!/usr/bin/env bb
;; Print decoded private tests for a cached LiveCodeBench problem.
;;
;; Usage: bb lcb-private-tests --id QUESTION_ID [--out PATH] [--format edn|json] [--private-only]

(require '[cheshire.core :as json])

;; lcb_fetch.bb must be loaded first (provides ensure-cache)

(defn- parse-args
  [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [[flag val & rest] args]
        (case flag
          "--id"           (recur rest (assoc opts :id val))
          "--out"          (recur rest (assoc opts :out val))
          "--format"       (recur rest (assoc opts :format val))
          "--private-only" (recur (if (nil? val) rest (cons val rest))
                                   (assoc opts :private-only true))
          "--help"         (recur (if (nil? val) rest (cons val rest))
                                   (assoc opts :help true))
          (do (binding [*out* *err*]
                (println "Unknown flag:" flag))
              (recur (if (nil? val) rest (cons val rest)) opts)))))))

(defn- usage []
  (println "Usage: bb lcb-private-tests --id QUESTION_ID [--out PATH] [--format edn|json] [--private-only]")
  (println)
  (println "Examples:")
  (println "  bb lcb-private-tests --id 1873_A")
  (println "  bb lcb-private-tests --id 1873_A --private-only --out /tmp/1873_A-private.edn")
  (println "  bb lcb-private-tests --id 1873_A --format json --out /tmp/1873_A-tests.json"))

(defn- render
  [data format]
  (case format
    "json" (json/generate-string data)
    (pr-str data)))

(defn -main [args]
  (let [{:keys [id out private-only help] :as opts} (parse-args args)
        format (or (:format opts) "edn")]
    (when help
      (usage)
      (System/exit 0))
    (when-not id
      (usage)
      (System/exit 1))
    (when-not (#{"edn" "json"} format)
      (binding [*out* *err*]
        (println "Invalid --format, expected edn or json:" format))
      (System/exit 1))

    (let [problem (ensure-problem-by-id id)]
      (if-not problem
        (do (binding [*out* *err*]
              (println "Problem not found:" id))
            (System/exit 1))
        (let [public-cases  (or (:public_test_cases problem) [])
              private-cases (or (:private_test_cases problem) [])
              payload       {:question_id        (:question_id problem)
                             :question_title     (:question_title problem)
                             :platform           (:platform problem)
                             :difficulty         (:difficulty problem)
                             :public_count       (count public-cases)
                             :private_count      (count private-cases)
                             :public_test_cases  public-cases
                             :private_test_cases private-cases}
              output        (render (if private-only private-cases payload) format)]
          (if out
            (do (spit out output)
                (println "Wrote" out))
            (println output))
          (when-not private-only
            (println "Found" (:public_count payload) "public and"
                     (:private_count payload) "private test cases for" id)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
