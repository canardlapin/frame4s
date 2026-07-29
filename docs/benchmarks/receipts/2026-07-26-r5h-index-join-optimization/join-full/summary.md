# frame4s prepared join index

Full prepared-join receipt. Prepared execution reuses the right-side immutable index and detached
right columns. Ordinary `ColumnarInterpreter.prepare` remains one-shot.

Decision: admit prepared join reuse for the designated shapes.

| Workload | Frozen one-shot ms/op | Same-run one-shot ms/op | Warm prepared ms/op | Warm speedup | Build ms | Cold first run ms | Cold speedup | Break-even runs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 0.104479 | 0.056562 | 0.038754 | 1.46x | 0.005766 | 0.044520 | 1.27x | 0.32 |
| joinOneToMany | 0.098302 | 0.051471 | 0.038283 | 1.34x | 0.005378 | 0.043661 | 1.18x | 0.41 |
| joinSparse | 0.015529 | 0.014902 | 0.007827 | 1.90x | 0.000798 | 0.008625 | 1.73x | 0.11 |
| joinSkewed | 0.094463 | 0.067969 | 0.035921 | 1.89x | 0.004729 | 0.040650 | 1.67x | 0.15 |

## Selection-gather allocation

| Workload | Frozen row-materialized B/op | One-shot selection-gather B/op | Reduction | Gate | Result | Warm prepared B/op |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 390458.820 | 113343.835 | 70.97% | >= 50% | pass | 63152.327 |
| joinOneToMany | 382202.592 | 113338.242 | 70.35% | >= 50% | pass | 63152.343 |
| joinSparse | 49856.414 | 26928.516 | 45.99% | >= 30% | pass | 19856.449 |
| joinSkewed | 362490.463 | 113339.312 | 68.73% | >= 50% | pass | 63218.068 |

Cold first run is `build + warm run`; break-even includes construction.
Exact output cardinalities and checksums are in `validation.tsv`; raw JMH
results are under `raw/`. Gate calculations use the same-run one-shot
scores; the frozen R5c Saddle-exact scores are shown as a drift check.
