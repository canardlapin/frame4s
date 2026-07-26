package frame4s.fs2

import cats.effect.Async
import cats.effect.Resource
import fs2.io.file.Files
import fs2.io.file.Path
import scala.NamedTuple
import frame4s.*

/** JVM-only, resource-safe CSV path adapter.
  *
  * The file is opened lazily by FS2 when a planned batch stream runs and is closed on completion,
  * failure, early termination, or cancellation. The portable module deliberately exposes only byte,
  * character, and bounded-string entry points.
  */
object CsvPathSource:
  /** Open and incrementally decode a CSV path inside a resource scope. */
  def resource[F[_]: Async](
      path: Path,
      options: CsvReadOptions
  ): Resource[F, CsvFrameSource[F]] =
    CsvFrameSource.bytes(Files.forAsync[F].readAll(path), options)

  /** Describe a typed CSV source without opening the file.
    *
    * The runtime opens the path and checks its header and values against the named-tuple schema
    * when the binding is acquired.
    */
  def binding[
      F[_]: Async,
      S <: NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      path: Path,
      settings: CsvSettings = CsvSettings()
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    SourceBinding(
      reference,
      resource(path, settings.options(descriptor.schema))
    )

/** JVM-only, resource-safe TSV path adapter with the same lifecycle as [[CsvPathSource]]. */
object TsvPathSource:
  def resource[F[_]: Async](
      path: Path,
      options: TsvReadOptions
  ): Resource[F, TsvFrameSource[F]] =
    TsvFrameSource.bytes(Files.forAsync[F].readAll(path), options)

  def binding[
      F[_]: Async,
      S <: NamedTuple.AnyNamedTuple
  ](
      reference: SourceRef,
      path: Path,
      settings: TsvSettings = TsvSettings()
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    SourceBinding(
      reference,
      resource(
        path,
        TsvReadOptions(
          descriptor.schema,
          settings.header,
          settings.nullTokens,
          settings.coercion,
          settings.batchSize
        )
      )
    )
