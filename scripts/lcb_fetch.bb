#!/usr/bin/env bb
;; Fetch and cache LiveCodeBench problems from HuggingFace.
;;
;; Downloads metadata/public tests for all JSONL files from
;; livecodebench/code_generation_lite and caches them locally.
;;
;; Cache layout:
;; - data/lcb_cache.edn                    -> metadata + public tests + source file
;; - data/lcb_private_test_cases/<id>.edn  -> decoded private tests per problem (on demand)
;;
;; NOTE: private_test_cases in the source dataset is obfuscated as
;; base64(zlib(pickle(json-string))). We decode and cache private tests when a
;; specific problem is requested (e.g. lcb-port / lcb-private-tests), rather
;; than storing all decoded private tests in one giant cache file.

(require '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def ^:private project-root
  (str (fs/parent (fs/parent (fs/absolutize *file*)))))

(def ^:private cache-path
  (str project-root "/data/lcb_cache.edn"))

(def ^:private private-cache-dir
  (str project-root "/data/lcb_private_test_cases"))

(def ^:private hf-base
  "https://huggingface.co/datasets/livecodebench/code_generation_lite/resolve/main")

(def ^:private jsonl-files
  ["test.jsonl" "test2.jsonl" "test3.jsonl"
   "test4.jsonl" "test5.jsonl" "test6.jsonl"])

(defn- strip-private-test-cases
  "Removes private_test_cases field value before JSON parse to avoid
   babashka/Jackson max-string issues on huge blobs."
  [line]
  (str/replace line
               #"\"private_test_cases\"\s*:\s*\"[^\"]*\""
               "\"private_test_cases\": \"\""))

(defn- extract-private-test-cases
  "Extracts obfuscated private_test_cases blob from a raw JSONL line."
  [line]
  (second (re-find #"\"private_test_cases\"\s*:\s*\"([^\"]*)\"" line)))

(defn- extract-question-id
  "Extracts question_id from a raw JSONL line without full JSON parse."
  [line]
  (second (re-find #"\"question_id\"\s*:\s*\"([^\"]+)\"" line)))

(defn- ubyte [b]
  (bit-and (int b) 0xFF))

(defn- le-int
  "Reads little-endian integer (n bytes) from byte array at idx."
  [^bytes bs idx n]
  (loop [i 0
         acc 0]
    (if (= i n)
      acc
      (recur (inc i)
             (+ acc (bit-shift-left (ubyte (aget bs (+ idx i))) (* 8 i)))))))

(defn- parse-pickled-string
  "Extracts a UTF-8 string from a pickle payload containing a Python str.
   Supports opcodes SHORT_BINUNICODE (0x8c), BINUNICODE (0x58), BINUNICODE8 (0x8d)."
  [^bytes bs]
  (let [n (alength bs)]
    (loop [i 0]
      (when (< i n)
        (let [op (ubyte (aget bs i))]
          (cond
            ;; PROTO: opcode + protocol-version byte
            (= op 0x80)
            (recur (+ i 2))

            ;; FRAME: opcode + 8-byte frame length
            (= op 0x95)
            (recur (+ i 9))

            ;; SHORT_BINUNICODE: opcode + 1-byte len + bytes
            (= op 0x8c)
            (let [len   (ubyte (aget bs (inc i)))
                  start (+ i 2)
                  end   (+ start len)]
              (if (<= end n)
                (String. bs start len "UTF-8")
                (recur (inc i))))

            ;; BINUNICODE: opcode + 4-byte little-endian len + bytes
            (= op 0x58)
            (let [len   (le-int bs (inc i) 4)
                  start (+ i 5)
                  end   (+ start len)]
              (if (<= end n)
                (String. bs start len "UTF-8")
                (recur (inc i))))

            ;; BINUNICODE8: opcode + 8-byte little-endian len + bytes
            (= op 0x8d)
            (let [len   (le-int bs (inc i) 8)
                  start (+ i 9)
                  end   (+ start len)]
              (if (<= end n)
                (String. bs start len "UTF-8")
                (recur (inc i))))

            ;; STOP
            (= op 0x2e)
            nil

            :else
            (recur (inc i))))))))

(defn- inflate-bytes
  "Inflates zlib-compressed bytes."
  [^bytes compressed]
  (with-open [in  (java.util.zip.InflaterInputStream.
                   (java.io.ByteArrayInputStream. compressed))
              out (java.io.ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))

(defn- decode-private-test-cases
  "Decodes private test cases from LCB format.

   Official loader behavior:
   1) try JSON directly
   2) fallback: base64 -> zlib -> pickle -> JSON"
  [private-blob]
  (if (str/blank? private-blob)
    []
    (try
      (vec (json/parse-string private-blob true))
      (catch Exception _
        (let [compressed (.decode (java.util.Base64/getDecoder) ^String private-blob)
              inflated   (inflate-bytes compressed)
              payload    (parse-pickled-string inflated)]
          (when-not payload
            (throw (ex-info "Could not parse private_test_cases pickle payload"
                            {:bytes-prefix (String. inflated 0 (min 32 (alength inflated)) "ISO-8859-1")})))
          (vec (json/parse-string payload true)))))))

(defn- parse-public-test-cases
  [tc-str]
  (if (and tc-str (not (str/blank? tc-str)))
    (vec (json/parse-string tc-str true))
    []))

(defn- parse-metadata
  [metadata-str]
  (if (and metadata-str (not (str/blank? metadata-str)))
    (json/parse-string metadata-str true)
    {}))

(defn- private-cache-path
  [question-id]
  (str private-cache-dir "/" question-id ".edn"))

(defn- write-private-cache!
  [question-id private-cases]
  (let [path (private-cache-path question-id)]
    (fs/create-dirs (fs/parent path))
    (spit path (pr-str private-cases))
    path))

(defn- read-private-cache
  [question-id]
  (let [path (private-cache-path question-id)]
    (when (fs/exists? path)
      (read-string (slurp path)))))

(defn- normalize-line
  "Parses and normalizes one raw JSONL line for metadata/public cache.
   private_test_cases is omitted here (cached on-demand per problem)."
  [line source-file]
  (let [sanitized (strip-private-test-cases line)
        parsed    (json/parse-string sanitized true)]
    (-> parsed
        (dissoc :private_test_cases)
        (assoc :public_test_cases (parse-public-test-cases (:public_test_cases parsed))
               :metadata (parse-metadata (:metadata parsed))
               :source_file source-file))))

(defn- fetch-jsonl
  "Fetches and normalizes one JSONL file from HuggingFace."
  [filename]
  (let [url  (str hf-base "/" filename)
        _    (binding [*out* *err*] (println "Fetching" url "..."))
        resp (http/get url {:follow-redirects true :as :stream})]
    (with-open [rdr (io/reader (:body resp))]
      (into []
            (map #(normalize-line % filename))
            (line-seq rdr)))))

(defn fetch-all
  "Fetches all problems and builds metadata/public-test cache payload.
   Returns vector of problem maps without decoded private tests."
  []
  (into []
        (mapcat fetch-jsonl)
        jsonl-files))

(defn load-cache
  "Loads cached metadata from disk, or nil if missing."
  []
  (when (fs/exists? cache-path)
    (read-string (slurp cache-path))))

(defn- cache-up-to-date?
  "Returns true when cache has expected metadata/public/source-file shape."
  [problems]
  (and (vector? problems)
       (seq problems)
       (every?
        (fn [p]
          (and (vector? (:public_test_cases p))
               (map? (:metadata p))
               (string? (:source_file p))))
        (take 20 problems))))

(defn save-cache
  "Saves metadata/public cache to disk."
  [problems]
  (spit cache-path (pr-str problems))
  (binding [*out* *err*]
    (println "Cached" (count problems) "problem metadata rows to" cache-path)))

(defn ensure-cache
  "Returns cached metadata, fetching/rebuilding when missing or stale."
  []
  (if-let [cached (load-cache)]
    (if (cache-up-to-date? cached)
      cached
      (do
        (binding [*out* *err*]
          (println "Cache is stale. Refreshing metadata/public cache..."))
        (let [problems (fetch-all)]
          (save-cache problems)
          problems)))
    (let [problems (fetch-all)]
      (save-cache problems)
      problems)))

(defn- fetch-private-test-cases-from-file
  "Scans one JSONL source file, finds question-id, decodes private tests.
   Returns vector of private test case maps or nil if not found in file."
  [filename question-id]
  (let [url  (str hf-base "/" filename)
        _    (binding [*out* *err*]
               (println "Fetching private tests for" question-id "from" filename "..."))
        resp (http/get url {:follow-redirects true
                            :as :stream
                            :connect-timeout 20000
                            :socket-timeout 600000})]
    (with-open [rdr (io/reader (:body resp))]
      (some (fn [line]
              (when (= question-id (extract-question-id line))
                (decode-private-test-cases (extract-private-test-cases line))))
            (line-seq rdr)))))

(defn problem-with-private-test-cases
  "Loads decoded private tests for a metadata problem map.
   If not yet cached locally, fetches from the source JSONL and writes cache.
   Returns the problem map with :private_test_cases attached."
  [problem]
  (if (contains? problem :private_test_cases)
    problem
    (let [question-id    (:question_id problem)
          cached-private (read-private-cache question-id)
          private-cases  (or cached-private
                             (or (when-let [src (:source_file problem)]
                                   (fetch-private-test-cases-from-file src question-id))
                                 ;; fallback if source_file missing
                                 (some #(fetch-private-test-cases-from-file % question-id) jsonl-files)
                                 []))]
      (when-not cached-private
        (write-private-cache! question-id private-cases))
      (assoc problem :private_test_cases private-cases))))

(defn ensure-problem-by-id
  "Returns one problem by question-id, with decoded private tests attached.
   Returns nil if not found."
  [question-id]
  (when-let [problem (first (filter #(= question-id (:question_id %)) (ensure-cache)))]
    (problem-with-private-test-cases problem)))

(defn -main [args]
  (let [force? (some #{"--force"} args)]
    (if force?
      (save-cache (fetch-all))
      (ensure-cache))
    (let [problems (load-cache)]
      (println (count problems) "problems cached"))))
