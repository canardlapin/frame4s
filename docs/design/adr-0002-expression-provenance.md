# ADR 0002: expression provenance

Status: accepted

Date: 2026-07-26

Decision issue: `bd-01KYF1PJGWDD74T0GJBM5AASV2`

## Context

An expression previously identified only a logical input side and column
position. A column expression captured from one frame could therefore be used
with another frame whose schema happened to satisfy the same static lookup.
With reordered fields, that produced a valid-looking plan that read the wrong
column.

The release contract requires rejection before an executable plan exists,
without making ordinary filter, projection, aggregation, or join syntax expose
type-level implementation machinery.

## Considered designs

| Design | Safety | Ordinary syntax | Helpers and joins | Diagnostic / implementation cost | Decision |
| --- | --- | --- | --- | --- | --- |
| Path-dependent frame identity only | Distinguishes frame values statically | Callback syntax stays unchanged | Singleton-value tags widened under ordinary lambda inference | Compiler diagnostics exposed unstable singleton types | Rejected alone |
| Phantom origin on expressions | Carries provenance through expression composition | No use-site annotation is needed | Origin unions model two-sided joins directly | Needs a stable per-frame origin and careful variance | Selected with a path-dependent origin |
| Rank-2 / scoped builder | Can prevent an origin from escaping its callback | Would change the callback and reusable-helper shape | Multi-source joins become substantially harder to read | Scala inference could not return origin-bearing tuples without additional wrappers | Rejected |
| Pure structured planning error | Prevents execution-time corruption | Typed transformations would return `Either` | Reusable values remain simple | Adds an error channel to every typed transformation and weakens the static claim | Retained only as the dynamic-API floor |

## Decision

Each `Frame[S]` has an abstract path-dependent `Origin` type. Expressions
created by its callback scope carry that origin as an erased phantom parameter.
Expression operators preserve one origin or form the union of two origins.
This gives join predicates the exact union of the corresponding left and right
origins.

The ordinary public view remains `Expr[A]`. The provenance-bearing
representation has no public constructor, and its resolved node is visible
only inside `frame4s`. Origin-free literals use a dedicated subtype, so a
literal can compose with any frame expression without widening the
frame-derived origin. Helpers that deliberately abstract over a scope are
origin-polymorphic:

```scala
def positive[Origin](
    row: Scope[People, Origin]
): ScopedExpr[Boolean, Origin] =
  row.col("id") > Expr.literal(0)
```

Typed projection and aggregation keep provenance in their internal expression
values but erase it from the resulting named-tuple schema arithmetic. A
`Frame[S]` retains the exact `SchemaDescriptor[S]` proven when it is
constructed, so execution does not need to re-derive a path-dependent
intermediate schema.

Dynamic expressions carry a private per-frame token plus their logical input
side. Typed-to-dynamic and dynamic-to-typed views share the token; every
transformation creates a fresh output token. Validation rejects expressions
from another frame even when the two plans and schemas are structurally
identical. Tokens are absent from expression IDs and `explain` output.

## Diagnostics and evidence

Negative compile tests require the first useful message to say that an
expression came from a different frame scope before any compiler elaboration
about match-type reduction. Separate compile tests cover duplicate output names
and key/aggregate collisions with the same diagnostic-ordering rule.

The accepted experiments cover:

- unchanged filter, project, aggregate, and two-sided join callbacks;
- origin-free literal reuse and origin-polymorphic helper functions;
- compile-time rejection for two same-shaped frames with fields in different
  orders;
- dynamic rejection for reordered frames and for two independently created
  frames with identical plans;
- unchanged 32-column schema derivation and final-column lookup;
- JVM compilation under the release court: Scala 3.7.4, sbt 1.10.5, and
  Temurin Java 21.0.11.

The implementation is estimated as a large R1 change because it touches every
typed expression boundary, plan normalization, dynamic validation, joins, and
execution descriptors. No migration is required for ordinary inferred
callbacks. Code that explicitly widens a frame-derived value to `Expr[A]`
intentionally erases its origin and cannot feed that value back into a typed
transformation; reusable column helpers should quantify over `Origin`.

## Consequences

- Cross-frame expression smuggling is a compile-time error on the typed API.
- Dynamic misuse is a structured `FrameError.InvalidExpressionScope` before a
  plan is returned.
- Literals remain reusable without a scope tag at the call site.
- Join predicates can mention only the selected left and right origins.
- Provenance cannot be forged through a public constructor or cast.
- The core pays a small additional type parameter and an internal token
  allocation per frame value; neither is present in stored column data.
