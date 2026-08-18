# Word Count Challenge

Build a module that maintains a running count of how often each word has
appeared across a stream of sentences, and answers point queries for the
count of a single word.

## Word semantics

A sentence's words are its **maximal runs of non-whitespace characters**.
No punctuation stripping or stemming is applied, so `dog.` and `dog` are
different words.

Two words are **the same word when they are equal ignoring case**. `Dog`,
`dog`, and `DOG` all count toward one total.

A word repeated within a single sentence counts **once per occurrence**. A
sentence containing no words, such as an empty or whitespace-only String,
records nothing.

## Workload

- Sentences arrive at roughly **10,000 per second**, averaging 15 words each.
- Word frequency is skewed: a small number of common words account for a large share of
  all occurrences.
- `get-count` runs at **thousands per second** and must complete within 5ms.

## Protocol

Your implementation must satisfy `word-count.protocol/WordCount`. See
`src/word_count/protocol.clj` for the contract.

Counts must be evenly distributed across the cluster, and both the write path
and `get-count` must stay efficient as the cluster grows. The skew in word
frequency means a design that concentrates the common words on one task will
not hold up.
