# Contributing

Thanks for helping frame4s.

Before opening a pull request:

1. Keep the core portable. `modules/core/shared` must not depend on Cats Effect,
   FS2, Apache Arrow Java, a production engine, or a platform-only API.
2. Represent public failure with a structured error ADT or an effect/resource
   boundary. Do not throw through pure public APIs.
3. Preserve explicit nullability, checked integral arithmetic, SQL
   three-valued boolean logic, and documented NaN semantics.
4. Add platform-independent tests under `shared` and run both JVM and Scala.js
   gates.
5. Run `sbt compileAll testAll`.

Contributions use the Developer Certificate of Origin. Add this line to each
commit message:

```text
Signed-off-by: Your Name <your.email@example.com>
```

By signing off, you certify the contribution under the
[Developer Certificate of Origin 1.1](https://developercertificate.org/).
