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

Each explanation assigns short local names such as `e1` and `e2`, then emits a
linear expression legend. The legend names operators and column positions but
renders literals only by physical type. It never prints literal values,
internal fingerprints, or expression provenance tokens. Reusing an expression
therefore grows the explanation with the number of distinct nodes, not with a
recursively expanded expression string.

Physical explain belongs to an execution boundary. On `FrameRuntime`,
`physicalExplain` describes materializing collection under the configured
`EnginePolicy`, while `streamPhysicalExplain` describes the reference cursor
and names any blocking operators. `collectWithReceipt` records the backend that
actually ran and any typed fallback reason. Treat that receipt as data;
`physicalPlan` and both explain strings are diagnostics rather than control-flow
protocols.

The always-available reference backend is the executable oracle.
`EnginePolicy.Auto` may select admitted in-process columnar kernels for
collection, `ReferenceOnly` bypasses them, and `RequireColumnar` rejects a
declined plan. Comparative performance claims require the versioned
[measurement court](https://github.com/canardlapin/frame4s/blob/main/docs/benchmarks/court.md)
and include
the workloads frame4s loses.

Continue to the [API reference](api-reference.md) when you need an exact symbol
or return type.
