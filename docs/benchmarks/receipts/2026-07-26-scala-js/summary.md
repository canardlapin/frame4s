# Scala.js semantic-path receipt

Recorded on 2026-07-26 with Scala 3.7.4, Scala.js 1.22.0, Node 24, sbt
1.10.5, and Temurin 21.0.11 as the build JDK.

This is a separate cross-platform smoke receipt, not a JMH result and not a
comparison between JVM and JavaScript runtime quality. It executes the same
100,000-row semantic reference plan used by `RelationalBench`, checks output
sizes, records the normalization rule, and proves all nine physical owners are
released.

Command:

```sh
sbt \
  'set coreJS / Test / scalaJSUseTestModuleInitializer := false' \
  'set coreJS / Test / scalaJSUseMainModuleInitializer := true' \
  'coreJS / Test / run'
```

Output:

```text
rows=100000 scan_rows=100000 project_rows=50000 aggregate_rows=10 join_rows=100000
normalization_rules=PushFilterThroughProject
utf8_checksum=200000
scan_ms=3.474
filter_project_ms=133.673
group_aggregate_ms=102.740
inner_join_ms=732.767
utf8_scan_ms=20.948
before_close=BufferSnapshot(9,9,0)
after_close=BufferSnapshot(0,0,9)
```

The reusable conformance and compile-time courts run as ordinary Scala.js
tests; this receipt supplements them with a bounded execution smoke.
