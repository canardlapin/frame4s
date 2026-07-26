# Operations and semantics

This is the public operation map for frame4s 0.1. `Frame[S]` is the only
transformation algebra. `Table[S]` is an owned read view: it decodes rows,
cells, columns, and case classes and renders bounded output, but it does not
filter, project, aggregate, sort, or join.

## Operation matrix

| Public operation | Typed result | Dynamic failure | Logical form | Order |
| --- | --- | --- | --- | --- |
| `select` | schema from named expressions | duplicate names, invalid scope | `Project` | preserves input |
| `withColumn` | appends one fresh field | collision, invalid scope | `Project` | preserves input |
| `rename`, `renameAll` | changes names in place | missing, duplicate request, or output collision | `Project` | preserves input |
| `drop`, `dropAll` | removes fields in input order | missing or duplicate request | `Project` | preserves input |
| `replace` (typed and dynamic), dynamic `replaceAll` | changes values/types in place | missing, duplicate request, or invalid scope | `Project` | preserves input |
| `filter` | unchanged | wrong/null boolean or invalid scope | `Filter` | preserves input |
| `distinct` | unchanged | none after binding | key-only `Aggregate` over every field | unspecified |
| `groupBy(...).aggregate(...)` | keys followed by aggregates | duplicate names, invalid expressions | `Aggregate` | unspecified |
| `innerJoin` | left fields then right fields | name/type/scope errors | inner `Join` | unspecified |
| `leftJoin` | left then nullable right | name/type/scope errors | left-outer `Join` | unspecified |
| `rightJoin` | nullable left then right | name/type/scope errors | swapped left-outer `Join` plus `Project` | unspecified |
| `semiJoin` | left schema | predicate/scope errors | left-semi `Join` | preserves left |
| `antiJoin` | left schema | predicate/scope errors | left-anti `Join` | preserves left |
| `unionAll` | exact same ordered schema | ordered schema mismatch | `UnionAll` | stable only when both inputs are stable |
| `sortBy` | unchanged | empty sort on dynamic path | `Sort` | sorted, stable for equal keys |
| `limit` | unchanged | negative count | `Limit` | preserves input |

The typed multiple-column projection conveniences are atomic: `renameAll`
renames two fields in one project and `dropAll` drops two fields in one
project. Duplicate requests are compile errors. The dynamic variants accept an
arbitrary non-empty request list and return
`FrameError.DuplicateColumnRequests`; dynamic `replaceAll` has the same
request rule. More complex typed projections use one `select`, which computes
the exact output schema without adding another algebra.

```scala
type Input = (id: Int, label: String, score: Option[Double])
type Output = (key: Int, name: String, score: Option[Double])

def renamed(input: Frame[Input]): Frame[Output] =
  input.renameAll("id" -> "key", "label" -> "name")
```

## Missing values and comparison

`Option[A]` is the only nullable typed column. Ordinary comparison follows SQL
three-valued logic: a null operand produces null. Filters require a total
boolean, so nullable comparisons must be made total explicitly with `isTrue`
or `nullSafeEq`. Null grouping keys coalesce.

Distinct and grouping compare logical values. Dictionary identity is ignored;
UTF-8 strings compare as decoded strings; timestamps require the same value and
unit; all NaN payloads of one width coalesce; and positive and negative zero
coalesce. Distinct does not promise which physical representative of an
equivalence class is returned.

## Joins

Join output names must be disjoint. frame4s never invents suffixes. Right join
is deliberately a theorem of left join: the inputs are swapped and one
projection restores the public left-then-right field order. There is no
`RightOuter` plan node.

Semi and anti joins use existential truth. A semi join emits a left row once
when any right-row predicate is true. An anti join emits it once when no
predicate is true. False and null do not count as matches, right duplicates
never multiply left rows, and evaluation stops after the first true result.

## Union and branch failures

`unionAll` requires equal field count, ordered names, data types, and
nullability. It preserves duplicates and consumes the left branch fully before
opening the right branch. A left failure suppresses right evaluation.
Normalization does not exchange the branches. The reference interpreter
streams active-branch batches and releases them on completion, failure, early
termination, or cancellation.

## Numeric operations

Integral arithmetic is checked and returns structured overflow or
divide-by-zero failures. There are no implicit casts.

`sqrt` exists only for `Float`, `Double`, `Option[Float]`, and
`Option[Double]`; it preserves width/nullability and follows IEEE behavior,
including signed zero, NaN, negative inputs, and infinities.

Population variance and standard deviation are named `variancePop` and
`stddevPop`. They use denominator `N`, ignore null observations, and return
null for empty or all-null inputs. There is intentionally no ambiguous
`variance` alias and no silent sample-statistic interpretation.

## Plans and execution

The closed public plan has `Source`, `Project`, `Filter`, `Join`, `UnionAll`,
`Aggregate`, `Sort`, and `Limit`. Convenience operations above explain as
those nodes. Logical explain is pure and deterministic; physical explain names
the selected backend, physical operators, blocking behavior, estimates, and
fallback. The reference interpreter is the semantic oracle. Execution,
streaming, and materialization occur only through an explicit scoped runtime.

The normative edge cases and ownership rules are in the
[semantic constitution](design/architecture.md#semantic-constitution).
