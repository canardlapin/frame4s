# The frame4s measurement court

The benchmark court is a non-published JVM project. It exists to falsify
performance claims, not to make the semantic reference interpreter look fast.
The semantic baseline is
[2026-07-26-r2-reference](receipts/2026-07-26-r2-reference/summary.md). The
current full optimized-kernel court and its admission decision are
[2026-07-26-r5-columnar-admitted-final](receipts/2026-07-26-r5-columnar-admitted-final/admission.md).
The exact-work follow-up is the
[R5c Saddle comparison](receipts/2026-07-26-r5c-saddle-exact/comparison.md);
the separate cross-runtime results are in the
[R5c Pandas receipt](receipts/2026-07-26-r5c-pandas/summary.md).

The court uses sbt-jmh 0.4.8 and JMH 1.37. Fixture construction, query
construction, validation, and teardown occur outside timed methods. Each
receipt contains:

- raw JMH JSON and console output;
- average-time and throughput results;
- `gc.alloc.rate.norm` allocation measurements;
- untimed output-row and checksum validation for every workload;
- the JDK, JVM flags, heap, dependency versions, hardware description,
  row count, backend, and fallback status.

Run a short harness check with:

```sh
scripts/benchmark-court.sh docs/benchmarks/receipts/local-quick quick
```

Run the full court on a quiet host with:

```sh
scripts/benchmark-court.sh docs/benchmarks/receipts/YYYY-MM-DD-host full
```

Set `FRAME4S_BENCHMARK_ROWS` to change the committed 1,000-row fixture size.
Changing the size creates a new receipt; it does not replace or silently
relabel an existing one.

## Workload contract

The committed court covers:

- primitive and nullable scan;
- direct UTF-8 and dictionary scan;
- filter and filter/project with arithmetic;
- low- and high-cardinality count/sum/mean/variance aggregation;
- one-to-one, one-to-many, sparse, and skewed joins;
- CSV decoding;
- owned-table construction; and
- bounded scalar decoding.

Union, distinct, and semi/anti joins are present with reference and internal
columnar-candidate workloads. An optimized backend enters only through the
reusable conformance boundary in `frame4s.testkit`; a capability gap is an
explicit residual, never a hidden reference fallback.

The specialized-array methods are lower bounds that clarify representation
overhead. Saddle 4.0.0-M14 participates only in comparable JVM shapes. The
materialized primitive projection, fused filter/project, and nullable grouped
sum produce the same output row counts and checksums as frame4s. The raw
primitive scan and scalar sum-only grouped reduction are retained as
lower-bound context; they do less work and are not ranked as equivalent
comparators. Saddle has no claimed comparator for the SQL duplicate-key join
cases, frame4s ownership, dictionary layout, or CSV acquisition.

Pandas is measured in a separate single-process Python court because JMH
cannot provide a shared-process timing environment across the JVM and CPython.
The court consumes the Scala validation receipt, requires exact output rows and
checksums wherever floating reduction order permits, and can attach the
frame4s JMH measurements for an explicitly cross-runtime ratio:

```sh
scripts/pandas-court.sh \
  --receipt docs/benchmarks/receipts/YYYY-MM-DD-pandas \
  --rows 1000 \
  --oracle-validation docs/benchmarks/receipts/YYYY-MM-DD-jvm/validation.tsv \
  --frame4s-jmh docs/benchmarks/receipts/YYYY-MM-DD-jvm/raw/jmh.json
```

The full four-statistic group workload is shape-validated rather than
checksum-ranked because the two runtimes use different legal floating-point
reduction orders. All other admitted Pandas comparisons require exact
checksums.

Scautable is intentionally absent from relational rankings. Its fixed-resource
macro path and its runtime typed path are discussed separately in
[ingestion and onboarding](ingestion-onboarding.md).

## Law court

`frame4s-testkit` is cross-built for JVM and Scala.js. Its generators
deliberately include invalid and wide schemas, optional and boundary numeric
values, Unicode, timestamps, dictionary encoding, legal batch splits,
duplicate/null/skewed join keys, checked expressions, and hostile CSV chunk
boundaries. ScalaCheck reports the replay seed and shrunk arguments for every
failure; domain shrinkers remove optional values and reduce numeric witnesses
without erasing the failing boundary.

The reusable backend boundary compares detached schema, rows, raw floating
bits, exact structured failures, and declared ordering. Unsupported
capabilities return a named residual with a backend receipt. The reference
backend runs through the same boundary, so an optimized backend does not earn
special access to interpreter internals.

## Receipt policy

Every designated workload is published, including losses. The semantic
reference path is the current best frame4s path and therefore the first R5
comparison baseline. After an optimized stage is admitted, the best admitted
frame4s result becomes the next baseline; later stages are never compared only
with the reference interpreter.

Performance thresholds are ratified in [budgets.md](budgets.md). A threshold,
fixture, or designated architectural-win workload may change only in a new
dated receipt that preserves the old result and states the reason before the
new optimization is judged.
