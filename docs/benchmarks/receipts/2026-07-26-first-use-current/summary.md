# Current first-use receipt

Recorded on 2026-07-26 with Temurin 21.0.11, sbt 1.10.5, Scala 3.7.4, and
macOS arm64. The warm incremental invocation completed in 3.03 seconds,
including sbt startup, project loading, resource acquisition, parsing, and
rendering.

The specimen:

1. derives the explicit schema
   `(id: Int, label: String, score: Option[Double])`;
2. validates a dynamic scan against that typed schema;
3. acquires a `CsvFrameSource[IO]` through `Resource`;
4. plans and consumes three rows in two batches; and
5. renders the result through the CSV sink.

The output preserves a quoted comma, a null optional score, `東京`, and `NaN`.
See `output.txt`, `timing.txt`, and `environment.properties` for raw evidence.

This is the before-R3 receipt. It makes no one-line import claim: source
binding and bounded table rendering still require the explicit bridge shown by
the executable example.
