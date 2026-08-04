# frame4s documentation

frame4s is an immutable, typed local dataframe library for Scala 3. Use it when
you want dataframe operations whose schemas appear in ordinary Scala types and
whose queries remain inspectable values until you execute them.

frame4s `0.1` is an early local-data release, not a production-scale
replacement for an analytical database. The reference interpreter is its
semantic oracle. Materializing collection uses `EnginePolicy.Auto`, which
selects admitted in-process columnar kernels and reports typed reference
fallbacks; streaming incrementally evaluates supported operators on the
reference path and names operations that must materialize. The performance
pages report matched wins and losses and state which public or internal
endpoint each result measures.

Start with the [three-minute quick start](quick-start.md). It constructs typed
data, runs a query, and prints a bounded result. Then continue with:

- [Practical dataframe pipelines](practical-pipelines.md) for filtering,
  derived columns, projection, and grouped summaries.
- [Typed CSV and TSV](csv-tsv.md) for file ingestion.
- [Arrow IPC on the JVM](arrow-ipc.md) for the optional coordinate, timestamp
  boundary, JVM option, and source lifetime.
- [Schemas and errors](schemas-and-errors.md) for nullability and compile-time
  column checking.
- [Relational operations](relational-operations.md) for joins, grouping,
  sorting, union, and distinct.
- [Execution and ownership](execution-and-ownership.md) before retaining or
  streaming materialized data.

The [API reference](api-reference.md) links the generated documentation for
the two cross-platform artifacts and the optional JVM Arrow artifact.
