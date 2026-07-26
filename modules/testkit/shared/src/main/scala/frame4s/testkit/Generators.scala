package frame4s.testkit

import frame4s.*
import org.scalacheck.{Arbitrary, Gen, Shrink}

final case class ConformanceRow(
    id: Int,
    key: Option[String],
    value: Option[Double],
    atMicros: Long
)

final case class JoinShape(
    leftKeys: Vector[Option[Int]],
    rightKeys: Vector[Option[Int]]
)

final case class CsvChunks(
    text: String,
    chunks: Vector[String],
    boundary: String
)

enum GeneratedExpression:
  case TotalThreshold(value: Int)
  case CheckedAdd(value: Int)

enum InvalidSchemaCase:
  case Duplicate
  case Empty
  case NullName

final case class InvalidSchemaFixture(
    fields: Vector[Field],
    expected: InvalidSchemaCase
)

object Frame4sGenerators:
  private val boundaryDoubles = Gen.oneOf(
    0.0,
    -0.0,
    Double.MinValue,
    Double.MaxValue,
    Double.MinPositiveValue,
    Double.NaN,
    Double.PositiveInfinity,
    Double.NegativeInfinity
  )

  val doubleValue: Gen[Double] =
    Gen.frequency(4 -> boundaryDoubles, 6 -> Arbitrary.arbitrary[Double])

  val intValue: Gen[Int] =
    Gen.frequency(
      3 -> Gen.oneOf(Int.MinValue, Int.MaxValue, -1, 0, 1),
      7 -> Arbitrary.arbitrary[Int]
    )

  val longValue: Gen[Long] =
    Gen.frequency(
      3 -> Gen.oneOf(Long.MinValue, Long.MaxValue, -1L, 0L, 1L),
      7 -> Arbitrary.arbitrary[Long]
    )

  val utf8Value: Gen[String] =
    Gen.oneOf("", "plain", "naïve", "東京", "🙂", "comma,value", "quote\"value", "line\nvalue")

  val optionalUtf8: Gen[Option[String]] =
    Gen.frequency(3 -> Gen.const(None), 7 -> utf8Value.map(Some.apply))

  val optionalDouble: Gen[Option[Double]] =
    Gen.frequency(3 -> Gen.const(None), 7 -> doubleValue.map(Some.apply))

  val row: Gen[ConformanceRow] =
    for
      id <- intValue
      key <- optionalUtf8
      value <- optionalDouble
      at <- longValue
    yield ConformanceRow(id, key, value, at)

  val rows: Gen[Vector[ConformanceRow]] =
    Gen.choose(0, 80).flatMap(size => Gen.listOfN(size, row).map(_.toVector))

  val dataType: Gen[DataType] =
    Gen.oneOf(
      DataType.Bool,
      DataType.Int32,
      DataType.Int64,
      DataType.Float32,
      DataType.Float64,
      DataType.Utf8,
      DataType.Timestamp(TimeUnit.Microsecond)
    )

  val uniqueFields: Gen[Vector[Field]] =
    Gen
      .choose(0, 72)
      .flatMap: width =>
        Gen
          .listOfN(width, Gen.zip(dataType, Arbitrary.arbitrary[Boolean]))
          .map: generated =>
            generated.zipWithIndex
              .map:
                case ((kind, nullable), index) =>
                  DynamicFrame.field(s"c$index", kind, nullable)
              .toVector

  val invalidSchema: Gen[InvalidSchemaFixture] =
    Gen.oneOf(
      InvalidSchemaFixture(
        Vector(
          DynamicFrame.field("duplicate", DataType.Int32),
          DynamicFrame.field("duplicate", DataType.Utf8)
        ),
        InvalidSchemaCase.Duplicate
      ),
      InvalidSchemaFixture(
        Vector(DynamicFrame.field("  ", DataType.Bool)),
        InvalidSchemaCase.Empty
      ),
      InvalidSchemaFixture(
        Vector(DynamicFrame.field(null, DataType.Int64)),
        InvalidSchemaCase.NullName
      )
    )

  val batchSize: Gen[Int] = Gen.choose(1, 17)

  val expression: Gen[GeneratedExpression] =
    Gen.frequency(
      5 -> intValue.map(GeneratedExpression.TotalThreshold.apply),
      5 -> Gen
        .oneOf(Int.MinValue, -1, 1, Int.MaxValue)
        .map(GeneratedExpression.CheckedAdd.apply)
    )

  val joinShape: Gen[JoinShape] =
    for
      leftSize <- Gen.choose(0, 60)
      rightSize <- Gen.choose(0, 60)
      domain <- Gen.choose(1, 8)
      left <- Gen.listOfN(
        leftSize,
        Gen.frequency(
          2 -> Gen.const(None),
          8 -> Gen.choose(0, domain).map(value => Some(value))
        )
      )
      right <- Gen.listOfN(
        rightSize,
        Gen.frequency(
          2 -> Gen.const(None),
          8 -> Gen.choose(0, domain).map(value => Some(value))
        )
      )
    yield JoinShape(left.toVector, right.toVector)

  val csvChunks: Gen[CsvChunks] =
    Gen.oneOf(
      CsvChunks(
        "id,label\r\n1,\"a,b\"\r\n",
        Vector("id,label\r", "\n1,\"a", ",b\"\r\n"),
        "CRLF and quoted delimiter"
      ),
      CsvChunks(
        "id,label\n1,\"a\"\"b\"\n",
        Vector("id,label\n1,\"a\"", "\"b\"\n"),
        "escaped quote"
      ),
      CsvChunks(
        "id,label\n1,東京🙂\n",
        Vector("id,label\n1,東", "京", "🙂\n"),
        "multibyte UTF-8"
      )
    )

  given Shrink[ConformanceRow] =
    Shrink: input =>
      val idShrinks = Shrink.shrink(input.id).map(value => input.copy(id = value))
      val keyShrinks =
        input.key.iterator.map(_ => input.copy(key = None))
      val valueShrinks =
        input.value.iterator.map(_ => input.copy(value = None))
      idShrinks ++ keyShrinks ++ valueShrinks

object ConformanceFixtures:
  type RowSchema = (
      id: Int,
      key: Option[String],
      value: Option[Double],
      at: TimestampMicros
  )

  private def sequence[A](
      values: Vector[Either[StorageError, A]]
  ): Either[StorageError, Vector[A]] =
    values.foldLeft[Either[StorageError, Vector[A]]](Right(Vector.empty)):
      case (result, value) => result.flatMap(acc => value.map(acc :+ _))

  private def batch(
      rows: Vector[ConformanceRow],
      tracker: BufferTracker
  ): Either[StorageError, RecordBatch] =
    val schema = summon[SchemaDescriptor[RowSchema]].schema
    val columns = Vector[Either[StorageError, ColumnArray]](
      ColumnArray.int32(rows.map(_.id).toArray, tracker = tracker),
      ColumnArray.utf8(
        rows.map(_.key.getOrElse("")).toArray,
        rows.map(_.key.nonEmpty).toArray,
        tracker
      ),
      ColumnArray.float64(
        rows.map(_.value.getOrElse(0.0)).toArray,
        rows.map(_.value.nonEmpty).toArray,
        tracker
      ),
      ColumnArray.timestamp(
        rows.map(_.atMicros).toArray,
        TimeUnit.Microsecond,
        tracker = tracker
      )
    )
    sequence(columns).flatMap: built =>
      RecordBatch(schema, built) match
        case right @ Right(_) => right
        case left @ Left(_)   =>
          built.foreach(_.close())
          left

  def table(
      rows: Vector[ConformanceRow],
      batchSize: Int,
      tracker: BufferTracker = new BufferTracker
  ): Either[StorageError, Table[RowSchema]] =
    if batchSize <= 0 then Left(StorageError.InvalidRange(0, batchSize, rows.size))
    else
      val built = rows.grouped(batchSize).toVector.map(batch(_, tracker))
      sequence(built).flatMap: batches =>
        Table[RowSchema](batches) match
          case right @ Right(_) => right
          case left @ Left(_)   =>
            batches.foreach(_.close())
            left
