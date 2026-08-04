# frame4s prepared join index

Full prepared-join receipt. Prepared execution reuses the right-side immutable index and detached
right columns. Ordinary `ColumnarInterpreter.prepare` remains one-shot.

Decision: do not generally admit prepared join reuse. At least one designated shape fails to improve both warm execution and construction plus first execution; the entry point remains package-internal experimental evidence.

| Workload | Frozen one-shot ms/op | Same-run one-shot ms/op | Warm prepared ms/op | Warm speedup | Build ms | Cold first run ms | Cold speedup | Break-even runs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 0.104479 | 0.109114 | 0.110210 | 0.99x | 0.004940 | 0.115150 | 0.95x | never |
| joinOneToMany | 0.098302 | 0.098443 | 0.131559 | 0.75x | 0.004881 | 0.136439 | 0.72x | never |
| joinSparse | 0.015529 | 0.017096 | 0.015544 | 1.10x | 0.000779 | 0.016322 | 1.05x | 0.50 |
| joinSkewed | 0.094463 | 0.094772 | 0.096348 | 0.98x | 0.004503 | 0.100850 | 0.94x | never |

Cold first run is `build + warm run`; break-even includes construction.
Exact output cardinalities and checksums are in `validation.tsv`; raw JMH
results are under `raw/`. Gate calculations use the same-run one-shot
scores; the frozen R5c Saddle-exact scores are shown as a drift check.
