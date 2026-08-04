# Compatibility, security, and contribution

The
[release policy](https://github.com/canardlapin/frame4s/blob/main/docs/release-policy.md)
defines the supported JDK, Node,
Scala, sbt, and Scala.js court, early-SemVer compatibility, and
single-maintainer continuity disclosure. Serialized plans are not a public wire
format. `0.1.0` becomes the first compatibility baseline only after it is
published.

Security reports follow
[SECURITY.md](https://github.com/canardlapin/frame4s/blob/main/SECURITY.md).
Do not disclose a
suspected vulnerability in a public issue. Ordinary correctness and performance
reports may use the public issue tracker.

Contributions follow
[CONTRIBUTING.md](https://github.com/canardlapin/frame4s/blob/main/CONTRIBUTING.md),
including the
DCO sign-off. Core changes must remain dependency-free and portable, preserve
the semantic constitution, and include JVM and Scala.js evidence. New backends
must pass the reusable oracle-conformance laws; benchmark complexity is admitted
only with a versioned receipt.

Published coordinates use version `@VERSION@`. JVM projects use `%%`:

```scala
libraryDependencies += "io.github.canardlapin" %% "frame4s-core" % "@VERSION@"
libraryDependencies += "io.github.canardlapin" %% "frame4s-fs2" % "@VERSION@"

// Optional Apache Arrow IPC adapter, JVM only.
libraryDependencies += "io.github.canardlapin" %% "frame4s-arrow" % "@VERSION@"
```

Scala.js projects use `%%%` for `frame4s-core` and `frame4s-fs2`.
`frame4s-arrow` has no Scala.js artifact and requires
`--add-opens=java.base/java.nio=ALL-UNNAMED` when an Arrow program starts.

Return to the [documentation overview](README.md) or start the
[first-result guide](quick-start.md).
