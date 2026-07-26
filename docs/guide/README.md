# frame4s documentation

frame4s is an immutable, typed local dataframe library for Scala 3. Use it when
you want dataframe operations whose schemas appear in ordinary Scala types and
whose queries remain inspectable values until you execute them.

frame4s `0.1` is an early release. It provides a semantic reference backend for
local data, not a production-scale replacement for an analytical database.
The public performance pages therefore report measured wins and losses without
claiming that every workload is faster than another library.

Start with the [three-minute quick start](quick-start.md). It constructs typed
data, runs a query, and prints a bounded result. Then continue with:

- [Typed CSV and TSV](csv-tsv.md) for file ingestion.
- [Schemas and errors](schemas-and-errors.md) for nullability and compile-time
  column checking.
- [Relational operations](relational-operations.md) for joins, grouping,
  sorting, union, and distinct.
- [Execution and ownership](execution-and-ownership.md) before retaining or
  streaming materialized data.

The [API reference](api-reference.md) links the generated documentation for
both published modules.
