# frame4s prepared join index

Quick provisional receipt. Prepared execution reuses the right-side immutable index and detached
right columns. Ordinary `ColumnarInterpreter.prepare` remains one-shot.

Decision: admit prepared join reuse for the designated shapes.

| Workload | Frozen one-shot ms/op | Same-run one-shot ms/op | Warm prepared ms/op | Warm speedup | Build ms | Cold first run ms | Cold speedup | Break-even runs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 0.104479 | 0.076752 | 0.047527 | 1.61x | 0.006472 | 0.054000 | 1.42x | 0.22 |
| joinOneToMany | 0.098302 | 0.189586 | 0.073518 | 2.58x | 0.006255 | 0.079773 | 2.38x | 0.05 |
| joinSparse | 0.015529 | 0.012300 | 0.009809 | 1.25x | 0.001150 | 0.010959 | 1.12x | 0.46 |
| joinSkewed | 0.094463 | 0.079772 | 0.043839 | 1.82x | 0.034820 | 0.078660 | 1.01x | 0.97 |

Cold first run is `build + warm run`; break-even includes construction.
Exact output cardinalities and checksums are in `validation.tsv`; raw JMH
results are under `raw/`. Gate calculations use the same-run one-shot
scores; the frozen R5c Saddle-exact scores are shown as a drift check.
