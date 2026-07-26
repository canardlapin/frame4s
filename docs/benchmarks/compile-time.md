# Compile-time and diagnostic court

The cross-built `frame4s-testkit` contains ordinary public-API specimens for a
narrow schema, 32 columns, and a wider 48-column practical schema. It also
tests the first diagnostic for missing, duplicated, mistyped, and
foreign-scope fields.

On 2026-07-26 the JVM specimen was measured on macOS arm64 with 14 logical
processors, Temurin 21.0.11, sbt 1.10.5, Scala 3.7.4, and warm isolated
dependency caches:

| Build | Command scope | Wall time |
|---|---|---:|
| clean | clean core JVM + testkit JVM, compile testkit tests | 10.68 s |
| incremental source change | recompile the compile-time specimen | 5.40 s |
| no-op incremental | confirm no source work | 2.61 s |

The exact command shape was:

```sh
sbt 'coreJVM/clean' 'testkitJVM/clean' 'testkitJVM/Test/compile'
sbt 'testkitJVM/Test/compile'
```

These are receipts, not cross-machine promises. A release cannot claim better
compile-time behavior without a new named-environment receipt.

The accepted diagnostics name the user field directly. In particular,
duplicate output aliases are rejected by an inline uniqueness witness without
printing internal `UniqueNames`/`NameAbsent` search trees, and a missing column
in the sorting specimen begins:

```text
Column 'missing' does not exist in this schema. Check the spelling or project
the column before this operation.
```

The same positive specimens and randomized conformance laws run on JVM and
Scala.js.
