#!/usr/bin/env bb
;; Report Q&A scoring results with mismatch details.
;; Usage: bb qa-report <challenge-name>
;;
;; Reads scores.edn from implementations/<name>/ and prints
;; a readable report showing which claims matched and which didn't.

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def project-root (str (fs/canonicalize ".")))

(defn -main [args]
  (when (empty? args)
    (println "Usage: bb qa-report <challenge-name>")
    (System/exit 1))
  (let [challenge-name (first args)
        scores-path (fs/path project-root "implementations" challenge-name "scores.edn")]
    (when-not (fs/exists? scores-path)
      (println "ERROR: scores.edn not found. Run bb qa-score first.")
      (System/exit 1))
    (let [{:keys [precision recall f1 pass-threshold passed
                  faithful covered answer-claims corpus-claims]}
          (edn/read-string (slurp (str scores-path)))]

      (println (format "=== Q&A Report: %s ===" challenge-name))
      (println (format "Precision: %.2f  Recall: %.2f  F1: %.2f  Threshold: %.2f  %s"
                       precision recall f1 pass-threshold
                       (if passed "PASS" "FAIL")))

      ;; Faithfulness: answer claims not supported by corpus
      (let [unfaithful (filterv identity
                         (map-indexed (fn [i f] (when-not f i)) faithful))]
        (println)
        (println (format "--- Faithfulness: %d/%d answer claims supported ---"
                         (count (filter true? faithful)) (count faithful)))
        (if (empty? unfaithful)
          (println "  All answer claims are faithful.")
          (do
            (println "  Unsupported answer claims:")
            (doseq [i unfaithful]
              (println (format "    %d. %s" (inc i) (nth answer-claims i)))))))

      ;; Completeness: corpus claims not covered by answer
      (let [uncovered (filterv identity
                        (map-indexed (fn [i c] (when-not c i)) covered))]
        (println)
        (println (format "--- Completeness: %d/%d corpus claims covered ---"
                         (count (filter true? covered)) (count covered)))
        (if (empty? uncovered)
          (println "  All corpus claims are covered.")
          (do
            (println "  Missing corpus claims:")
            (doseq [i uncovered]
              (println (format "    %d. %s" (inc i) (nth corpus-claims i))))))))))
