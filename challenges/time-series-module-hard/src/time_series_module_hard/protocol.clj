(ns time-series-module-hard.protocol
  "Protocol definition for the time-series-module-hard challenge.")

(defprotocol TimeSeriesModuleHard
  "Protocol for time-series latency tracking."
  (record-latency! [this url render-millis timestamp-millis]
    "Record a render latency measurement.")
  (get-stats-for-range [this url start-minute-bucket end-minute-bucket]
    "Returns stats for a minute bucket range [start, end). Results must be returned
    as a map with keys :cardinality, :total, :min-latency-millis, :max-latency-millis."))
