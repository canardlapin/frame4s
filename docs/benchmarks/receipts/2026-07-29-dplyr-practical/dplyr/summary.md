# dplyr practical pipeline comparison

Full separate-process dplyr timing receipt.

Both eager pipelines use a prebuilt tibble and match the Scala semantic oracle's
output row count, ordered non-floating/null structure, and floating-column
signatures before timing. Row-weighted moments bind floating values to output
order; all floating statistics use a 1e-10 relative tolerance.

| Workload | dplyr median | Range | frame4s JMH | dplyr/frame4s |
|---|---:|---:|---:|---:|
| `filterWithColumnsSelect` | 1.339844 ms | 1.292969-1.691406 ms | 0.487033 ms | 2.75x |
| `selectGroupSummarise` | 1.593750 ms | 1.546875-1.628906 ms | 0.722191 ms | 2.21x |

The runtimes execute in separate processes. Ratios are descriptive and retain every
visible loss; JVM allocation is reported in the sibling frame4s receipt.
