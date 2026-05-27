(ns rama-challenges.shared
  "Challenge implementations may use this namespace.")

(def REPLACE-TICK-DEPOTS
  "When true, modules should declare tick depots as regular depots instead,
   allowing tests to manually trigger ticks. Set to true via `with-redefs`
   before module creation in tests that need to control timing. This var is
   used by private tests when evaluating challenge implementations."
  false)
