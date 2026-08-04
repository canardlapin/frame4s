# frame4s prepared join index

Full prepared-join receipt. Prepared execution reuses the right-side immutable index and detached
right columns. Ordinary `ColumnarInterpreter.prepare` remains one-shot.

Decision: admit prepared join reuse for the designated shapes.

| Workload | Frozen one-shot ms/op | Same-run one-shot ms/op | Warm prepared ms/op | Warm speedup | Build ms | Cold first run ms | Cold speedup | Break-even runs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 0.104479 | 0.050819 | 0.038709 | 1.31x | 0.005633 | 0.044342 | 1.15x | 0.47 |
| joinOneToMany | 0.098302 | 0.050764 | 0.037504 | 1.35x | 0.005495 | 0.042999 | 1.18x | 0.41 |
| joinSparse | 0.015529 | 0.010034 | 0.007761 | 1.29x | 0.000798 | 0.008560 | 1.17x | 0.35 |
| joinSkewed | 0.094463 | 0.050411 | 0.036441 | 1.38x | 0.004657 | 0.041098 | 1.23x | 0.33 |

## Selection-gather allocation

| Workload | Frozen row-materialized B/op | One-shot selection-gather B/op | Reduction | Gate | Result | Warm prepared B/op |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 390458.820 | 113340.237 | 70.97% | >= 50% | pass | 63146.227 |
| joinOneToMany | 382202.592 | 113343.671 | 70.34% | >= 50% | pass | 63146.161 |
| joinSparse | 49856.414 | 26928.442 | 45.99% | >= 30% | pass | 19856.444 |
| joinSkewed | 362490.463 | 113338.208 | 68.73% | >= 50% | pass | 63218.092 |

Cold first run is `build + warm run`; break-even includes construction.
Exact output cardinalities and checksums are in `validation.tsv`; raw JMH
results are under `raw/`. Gate calculations use the same-run one-shot
scores; the frozen R5c Saddle-exact scores are shown as a drift check.
