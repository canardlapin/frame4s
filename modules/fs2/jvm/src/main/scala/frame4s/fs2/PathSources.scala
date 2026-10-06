package frame4s.fs2

import cats.effect.Async
import cats.effect.Resource
import fs2.io
import fs2.io.file.Path
import fs2.Stream
import java.io.InputStream
import java.nio.file.Files
import scala.NamedTuple
import frame4s.*

private[fs2] object PathByteStream:
  private val ChunkSize = 64 * 1024

  def apply[F[_]](acquire: F[InputStream])(using F: Async[F]): Stream[F, Byte] =
    val input = Resource.make(
      AdapterFailureBoundary.sourceEffect(acquire, SourceError.Read.apply)
    ): value =>
      AdapterFailureBoundary.sourceEffect(
        F.blocking(value.close()),
        SourceError.Close.apply
      )
    AdapterFailureBoundary.sourceStream(
      Stream
        .resource(input)
        .flatMap: value =>
          io.readInputStream(
            F.pure(value),
            ChunkSize,
            closeAfterUse = false
          ),
      SourceError.Read.apply
    )

private def readPath[F[_]: Async](path: Path): Stream[F, Byte] =
  PathByteStream(Async[F].blocking(Files.newInputStream(path.toNioPath)))

/** Bounded header discovery before the caller explicitly constructs a runtime schema. */
object DelimitedHeaderPath:
  def read[F[_]: Async](
      path: Path,
      options: DelimitedHeaderOptions = DelimitedHeaderOptions()
  ): F[Either[SourceError, DelimitedHeader]] =
    DelimitedHeader.bytes(readPath(path), options)

/** JVM-only, resource-safe CSV path adapter.
  *
  * The file is opened lazily by FS2 when a planned batch stream runs and is closed on completion,
  * failure, early termination, or cancellation. The portable module deliberately exposes only byte,
  * character, and bounded-string entry points.
  */
object CsvPathSource:
  /** Open and incrementally decode a CSV path inside a resource scope.
    *
    * Filesystem failures are `SourceError.Read`; malformed bytes remain the distinct
    * `SourceError.InvalidUtf8` case.
    */
  def resource[F[_]: Async](
      path: Path,
      options: CsvReadOptions
  ): Resource[F, CsvFrameSource[F]] =
    CsvFrameSource.bytes(readPath(path), options)

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

  /** Describe one typed CSV path without choosing a multi-source identity. */
  def binding[
      F[_]: Async,
      S <: NamedTuple.AnyNamedTuple
  ](
      path: Path
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    binding(path, CsvSettings())

  def binding[
      F[_]: Async,
      S <: NamedTuple.AnyNamedTuple
  ](
      path: Path,
      settings: CsvSettings
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    SourceBinding.singleSource(
      SourceRef.singleSourceScan,
      resource(path, settings.options(descriptor.schema))
    )

/** JVM-only, resource-safe TSV path adapter with the same lifecycle as [[CsvPathSource]]. */
object TsvPathSource:
  def resource[F[_]: Async](
      path: Path,
      options: TsvReadOptions
  ): Resource[F, TsvFrameSource[F]] =
    TsvFrameSource.bytes(readPath(path), options)

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
          schema = descriptor.schema,
          header = settings.header,
          nullPolicy = settings.nullPolicy,
          coercion = settings.coercion,
          batchSize = settings.batchSize,
          limits = settings.limits
        )
      )
    )

  /** Describe one typed TSV path without choosing a multi-source identity. */
  def binding[
      F[_]: Async,
      S <: NamedTuple.AnyNamedTuple
  ](
      path: Path
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    binding(path, TsvSettings())

  def binding[
      F[_]: Async,
      S <: NamedTuple.AnyNamedTuple
  ](
      path: Path,
      settings: TsvSettings
  )(using descriptor: SchemaDescriptor[S]): SourceBinding[F, S] =
    SourceBinding.singleSource(
      SourceRef.singleSourceScan,
      resource(
        path,
        TsvReadOptions(
          schema = descriptor.schema,
          header = settings.header,
          nullPolicy = settings.nullPolicy,
          coercion = settings.coercion,
          batchSize = settings.batchSize,
          limits = settings.limits
        )
      )
    )
