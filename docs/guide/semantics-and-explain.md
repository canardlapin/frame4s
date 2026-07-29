# Semantic constitution and explain

The
[semantic constitution](https://github.com/canardlapin/frame4s/blob/main/docs/design/architecture.md#semantic-constitution)
specifies SQL three-valued logic, checked integral arithmetic, logical grouping
equivalence, timestamp and UTF-8 rules, ordering, IEEE square root, and
population statistics.

Logical explain is a pure view of a query value:

```scala mdoc:compile-only
import frame4s.*

type Row = (id: Int, value: Option[Double])

def explanation(input: Frame[Row]): String =
  input
    .filter(row => row.col("id") > 0)
    .select(row =>
      (
        row.col("id"),
        row.col("value").sqrt.as("root")
      )
    )
    .explain
```

Physical explain belongs to the selected execution boundary and names physical
operators, blocking behavior, estimates, and fallback. The always-available
reference backend is the executable oracle. Comparative performance claims
require the versioned
[measurement court](https://github.com/canardlapin/frame4s/blob/main/docs/benchmarks/court.md)
and include
the workloads frame4s loses.

Continue to the [API reference](api-reference.md) when you need an exact symbol
or return type.
