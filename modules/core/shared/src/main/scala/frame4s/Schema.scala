package frame4s

import scala.NamedTuple
import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, erasedValue, error}

opaque type ColumnId = String

object ColumnId:
  private[frame4s] def derived(name: String): ColumnId = s"column:$name"

  extension (id: ColumnId) def value: String = id

opaque type SourceId = String

object SourceId:
  private[frame4s] def unsafe(value: String): SourceId = value

  extension (id: SourceId) def value: String = id

/** A malformed scalar value rejected before it can enter an expression or storage value. */
enum ValueError:
  case NullUtf8
  case NullLiteral
  case NullDataType
  case NullTimeUnit

  def message: String = this match
    case NullUtf8     => "raw null is not a UTF-8 value; use None for nullable data"
    case NullLiteral  => "literal value is null"
    case NullDataType => "literal data type is null"
    case NullTimeUnit => "literal timestamp unit is null"

/** A deliberate boundary failure for direct typed expression constructors.
  *
  * Use a checked smart constructor when a value comes from Java or another untrusted boundary. This
  * exception exists for callers compiled without explicit nulls that place raw null inside a
  * statically non-null `String` or `Option[String]`.
  */
final case class InvalidValueFailure(error: ValueError)
    extends IllegalArgumentException(error.message)

/** A string that has passed frame4s's raw-null check.
  *
  * The wrapper is the payload of UTF-8 literals and scalars. Construct it with [[Utf8Value.from]];
  * the underlying non-null string is available through `value`.
  */
opaque type Utf8Value = String

object Utf8Value:
  def from(value: String): Either[ValueError, Utf8Value] =
    if value == null then Left(ValueError.NullUtf8) else Right(value)

  private[frame4s] def checked(value: String): Utf8Value =
    from(value).fold(error => throw InvalidValueFailure(error), identity)

  extension (utf8: Utf8Value) def value: String = utf8

/** Portable physical types supported by frame4s storage and logical plans.
  *
  * Nullability is carried separately by [[Field.nullable]] and represented as `Option[A]` in typed
  * schemas.
  */
enum DataType:
  case Bool
  case Int32
  case Int64
  case Float32
  case Float64
  case Utf8
  case Timestamp(unit: TimeUnit)

enum TimeUnit:
  case Second
  case Millisecond
  case Microsecond
  case Nanosecond

opaque type TimestampMicros = Long

object TimestampMicros:
  def apply(value: Long): TimestampMicros = value

  extension (value: TimestampMicros) def toLong: Long = value

/** One closed, singleton-typed request in an atomic [[Frame.renameAll]] operation. */
final class RenameRequest[
    From <: String & Singleton,
    To <: String & Singleton
] private (
    val from: From,
    val to: To
)

object RenameRequest:
  def apply[
      From <: String & Singleton,
      To <: String & Singleton
  ](from: From, to: To): RenameRequest[From, To] =
    new RenameRequest(from, to)

/** One closed, singleton-typed request in an atomic [[Frame.dropAll]] operation. */
final class DropRequest[Name <: String & Singleton] private (val name: Name)

object DropRequest:
  def apply[Name <: String & Singleton](name: Name): DropRequest[Name] =
    new DropRequest(name)

/** One same-named key in a typed multikey using join. */
final class UsingKey[Name <: String & Singleton] private (val name: Name)

object UsingKey:
  def apply[Name <: String & Singleton](name: Name): UsingKey[Name] =
    new UsingKey(name)

/** One ordered runtime schema field.
  *
  * Use [[DynamicFrame.field]] to construct fields for a runtime schema. Typed schemas derive their
  * fields from a named tuple through [[SchemaDescriptor]].
  */
final case class Field private[frame4s] (
    id: ColumnId,
    name: String,
    dataType: DataType,
    nullable: Boolean
)

/** A structural error that prevents construction of a runtime [[Schema]]. */
enum SchemaError:
  case NullFields
  case NullField(index: Int)
  case NullFieldName(index: Int)
  case NullFieldType(index: Int)
  case NullTimestampUnit(index: Int)
  case EmptyFieldName(index: Int)
  case DuplicateFieldName(name: String)

  def message: String = this match
    case NullFields               => "schema fields are null"
    case NullField(index)         => s"field at index $index is null"
    case NullFieldName(index)     => s"field at index $index has a null name"
    case NullFieldType(index)     => s"field at index $index has a null data type"
    case NullTimestampUnit(index) => s"timestamp field at index $index has a null unit"
    case EmptyFieldName(index)    => s"field at index $index has an empty name"
    case DuplicateFieldName(name) => s"field '$name' occurs more than once"

/** An immutable, ordered runtime schema with unique, non-empty field names.
  *
  * A `Schema` is the runtime witness paired with every typed `Frame[S]` and `Table[S]`. Its field
  * order is significant; exact typed binding checks names, types, nullability, and order.
  */
final class Schema private (val fields: Vector[Field]):
  def size: Int = fields.size

  def field(name: String): Option[Field] = fields.find(_.name == name)

  override def equals(other: Any): Boolean = other match
    case that: Schema => fields == that.fields
    case _            => false

  override def hashCode(): Int = fields.hashCode()

  override def toString: String =
    fields
      .map: field =>
        val suffix = if field.nullable then "?" else ""
        s"${field.name}: ${field.dataType}$suffix"
      .mkString("Schema(", ", ", ")")

object Schema:
  def apply(fields: Vector[Field]): Either[SchemaError, Schema] =
    if fields == null then Left(SchemaError.NullFields)
    else
      // These checks must precede `trim` and every data-type match. A Field can reach here from
      // DynamicFrame.field or a foreign-format decoder.
      fields.zipWithIndex.collectFirst {
        case (field, index) if field == null          => SchemaError.NullField(index)
        case (field, index) if field.name == null     => SchemaError.NullFieldName(index)
        case (field, index) if field.dataType == null => SchemaError.NullFieldType(index)
        case (field, index) if field.dataType match
              case DataType.Timestamp(unit) => unit == null
              case _                        => false
            =>
          SchemaError.NullTimestampUnit(index)
        case (field, index) if field.name.trim.isEmpty => SchemaError.EmptyFieldName(index)
      } match
        case Some(error) => Left(error)
        case None        =>
          fields
            .groupMapReduce(_.name)(_ => 1)(_ + _)
            .collectFirst { case (name, count) if count > 1 => name } match
            case Some(name) => Left(SchemaError.DuplicateFieldName(name))
            case None       => Right(new Schema(fields))

  private[frame4s] def unsafe(fields: Vector[Field]): Schema =
    apply(fields).fold(error => throw new IllegalArgumentException(error.message), identity)

/** Sealed mapping between a Scala field type and its frame4s physical representation.
  *
  * Built-in instances cover the supported scalar types and `Option[A]`. Applications cannot add an
  * unrelated physical encoding behind an existing typed schema.
  */
sealed trait ColumnType[A]:
  def dataType: DataType
  def nullable: Boolean
  private[frame4s] def literal(value: A): Either[ValueError, LiteralValue]

object ColumnType:
  given ColumnType[Boolean] with
    val dataType = DataType.Bool
    val nullable = false
    private[frame4s] def literal(value: Boolean) = Right(LiteralValue.Bool(value))

  given ColumnType[Int] with
    val dataType = DataType.Int32
    val nullable = false
    private[frame4s] def literal(value: Int) = Right(LiteralValue.Int32(value))

  given ColumnType[Long] with
    val dataType = DataType.Int64
    val nullable = false
    private[frame4s] def literal(value: Long) = Right(LiteralValue.Int64(value))

  given ColumnType[Float] with
    val dataType = DataType.Float32
    val nullable = false
    private[frame4s] def literal(value: Float) = Right(LiteralValue.Float32(value))

  given ColumnType[Double] with
    val dataType = DataType.Float64
    val nullable = false
    private[frame4s] def literal(value: Double) = Right(LiteralValue.Float64(value))

  given ColumnType[String] with
    val dataType = DataType.Utf8
    val nullable = false
    private[frame4s] def literal(value: String) = LiteralValue.utf8(value)

  given ColumnType[TimestampMicros] with
    val dataType = DataType.Timestamp(TimeUnit.Microsecond)
    val nullable = false
    private[frame4s] def literal(value: TimestampMicros) =
      Right(LiteralValue.Timestamp(value.toLong, TimeUnit.Microsecond))

  given [A](using value: ColumnType[A]): ColumnType[Option[A]] with
    val dataType = value.dataType
    val nullable = true
    private[frame4s] def literal(input: Option[A]) = input match
      case Some(actual) => value.literal(actual)
      case None         => Right(LiteralValue.Null(value.dataType))

/** Derives the runtime [[Schema]] for a named-tuple type.
  *
  * Sealed on purpose: every typed combinator reads `schema` while the compiler reasons about `S`,
  * so an externally supplied instance could hand a `Frame[S]` a runtime schema unrelated to `S`.
  * `schema` stays publicly readable; only implementation is restricted.
  */
sealed trait SchemaDescriptor[S <: NamedTuple.AnyNamedTuple]:
  def schema: Schema

object SchemaDescriptor:
  final class Evidence[
      S <: NamedTuple.AnyNamedTuple
  ] @scala.annotation.publicInBinary private[frame4s] (
      val schema: Schema
  ) extends SchemaDescriptor[S]

  private[frame4s] def field[A](name: String, value: ColumnType[A]): Field =
    Field(
      ColumnId.derived(name),
      name,
      value.dataType,
      value.nullable
    )

  /** Derive the runtime schema for a named-tuple type. */
  transparent inline given derived[
      S <: NamedTuple.AnyNamedTuple
  ]: SchemaDescriptor[S] =
    ${ SchemaMacros.schemaDescriptor[S] }

@implicitNotFound(
  "Column '${Name}' does not exist in schema fields ${Names}. Check the spelling or project the column before this operation."
)
private[frame4s] trait ColumnAt[
    Names <: Tuple,
    Values <: Tuple,
    Name <: String
]:
  def index: Int

private[frame4s] object ColumnAt:
  given head[
      Name <: String & Singleton,
      Names <: Tuple,
      Value,
      Values <: Tuple
  ]: ColumnAt[Name *: Names, Value *: Values, Name] with
    val index = 0

  given tail[
      Head <: String,
      Names <: Tuple,
      Value,
      Values <: Tuple,
      Name <: String
  ](using
      notSame: scala.util.NotGiven[Head =:= Name],
      next: ColumnAt[Names, Values, Name]
  ): ColumnAt[Head *: Names, Value *: Values, Name] with
    val index = next.index + 1

@implicitNotFound(
  "Column '${Name}' already exists in schema fields ${Names}. Choose a new output name."
)
sealed private[frame4s] trait ColumnAbsent[
    Names <: Tuple,
    Values <: Tuple,
    Name <: String
]

private[frame4s] object ColumnAbsent:
  given [
      Names <: Tuple,
      Values <: Tuple,
      Name <: String
  ](using
      scala.util.NotGiven[ColumnAt[Names, Values, Name]]
  ): ColumnAbsent[Names, Values, Name] with {}

type FieldType[
    Names <: Tuple,
    Values <: Tuple,
    Name <: String
] = (Names, Values) match
  case (Name *: names, value *: values) => value
  case (_ *: names, _ *: values)        => FieldType[names, values, Name]
  case (EmptyTuple, EmptyTuple)         => Nothing

@implicitNotFound(
  "Column '${Name}' does not exist in schema fields ${Names}. Check the spelling or project the column before this operation."
)
private[frame4s] trait ColumnLookup[
    Names <: Tuple,
    Values <: Tuple,
    Name <: String
]:
  def index: Int

private[frame4s] object ColumnLookup:
  final class Evidence[
      Names <: Tuple,
      Values <: Tuple,
      Name <: String
  ](val index: Int)
      extends ColumnLookup[Names, Values, Name]

  transparent inline given derived[
      Names <: Tuple,
      Values <: Tuple,
      Name <: String
  ]: ColumnLookup[Names, Values, Name] =
    ${ SchemaMacros.columnLookup[Names, Values, Name] }

type ContainsName[Names <: Tuple, Name <: String] <: Boolean = Names match
  case EmptyTuple   => false
  case Name *: tail => true
  case head *: tail => ContainsName[tail, Name]

@implicitNotFound(
  "Output column names ${Names} must be unique. Rename the duplicated projection, grouping, or aggregate alias."
)
sealed private[frame4s] trait UniqueNames[Names <: Tuple]

private[frame4s] object UniqueNames:
  final class Evidence[Names <: Tuple] extends UniqueNames[Names]

  transparent inline given derived[Names <: Tuple]: UniqueNames[Names] =
    check[Names]
    new Evidence[Names]

  private inline def check[Names <: Tuple]: Unit =
    inline erasedValue[Names] match
      case _: EmptyTuple     => ()
      case _: (head *: tail) =>
        inline erasedValue[ContainsName[tail, head & String]] match
          case _: true =>
            error(
              "Output column '" + constValue[head & String] +
                "' is duplicated. Rename the projection, grouping, or aggregate alias."
            )
          case _: false => check[tail]

@implicitNotFound(
  "Column requests ${Names} must be unique. Remove the duplicated rename, drop, or using key."
)
sealed private[frame4s] trait UniqueRequestNames[Names <: Tuple]

private[frame4s] object UniqueRequestNames:
  final class Evidence[Names <: Tuple] extends UniqueRequestNames[Names]

  transparent inline given derived[Names <: Tuple]: UniqueRequestNames[Names] =
    check[Names]
    new Evidence[Names]

  private inline def check[Names <: Tuple]: Unit =
    inline erasedValue[Names] match
      case _: EmptyTuple     => ()
      case _: (head *: tail) =>
        inline erasedValue[ContainsName[tail, head & String]] match
          case _: true =>
            error(
              "Column request '" + constValue[head & String] +
                "' is duplicated. Keep each requested source column or using key once."
            )
          case _: false => check[tail]

@implicitNotFound("At least one typed column request is required.")
sealed private[frame4s] trait NonEmptyRequests[Requests <: Tuple]

private[frame4s] object NonEmptyRequests:
  given [Head, Tail <: Tuple]: NonEmptyRequests[Head *: Tail] with {}

@implicitNotFound(
  "Rename target '${Name}' collides with an unrenamed column in ${Names}. Rename that source in the same atomic request or choose another target."
)
sealed private[frame4s] trait RenameTargetAbsent[
    Names <: Tuple,
    Name <: String
]

private[frame4s] object RenameTargetAbsent:
  given [Names <: Tuple, Name <: String](using
      scala.util.NotGiven[ContainsName[Names, Name] =:= true]
  ): RenameTargetAbsent[Names, Name] with {}

@implicitNotFound(
  "Rename targets ${Targets} overlap the unrenamed output columns ${Remaining}."
)
sealed private[frame4s] trait RenameTargetsDisjoint[
    Targets <: Tuple,
    Remaining <: Tuple
]

private[frame4s] object RenameTargetsDisjoint:
  given [Remaining <: Tuple]: RenameTargetsDisjoint[EmptyTuple, Remaining] with {}

  given cons[
      Name <: String & Singleton,
      Tail <: Tuple,
      Remaining <: Tuple
  ](using
      absent: RenameTargetAbsent[Remaining, Name],
      tail: RenameTargetsDisjoint[Tail, Remaining]
  ): RenameTargetsDisjoint[Name *: Tail, Remaining] with {}

/*
 * ColumnAt remains the presence/absence witness used by joins and withColumn.
 * ColumnLookup is the user-facing lookup path: its inline failure is emitted
 * before downstream selection/schema evidence can mask the offending name.
 */
type SchemaFieldType[S <: NamedTuple.AnyNamedTuple, Name <: String] =
  FieldType[NamedTuple.Names[S], NamedTuple.DropNames[S], Name]

type SelectionNames[Expressions <: Tuple] <: Tuple = Expressions match
  case EmptyTuple                     => EmptyTuple
  case NamedExpr[name, value] *: tail => name *: SelectionNames[tail]

type SelectionValues[Expressions <: Tuple] <: Tuple = Expressions match
  case EmptyTuple                     => EmptyTuple
  case NamedExpr[name, value] *: tail => value *: SelectionValues[tail]

type SelectedSchema[Expressions <: Tuple] = NamedTuple.NamedTuple[
  SelectionNames[Expressions],
  SelectionValues[Expressions]
]

type AppendedSchema[S <: NamedTuple.AnyNamedTuple, Name <: String, Value] = NamedTuple.NamedTuple[
  Tuple.Concat[NamedTuple.Names[S], Name *: EmptyTuple],
  Tuple.Concat[NamedTuple.DropNames[S], Value *: EmptyTuple]
]

type ConcatSchema[
    Left <: NamedTuple.AnyNamedTuple,
    Right <: NamedTuple.AnyNamedTuple
] = NamedTuple.NamedTuple[
  Tuple.Concat[NamedTuple.Names[Left], NamedTuple.Names[Right]],
  Tuple.Concat[NamedTuple.DropNames[Left], NamedTuple.DropNames[Right]]
]

type Nullable[A] = A match
  case Option[value] => Option[value]
  case _             => Option[A]

type NullableTuple[Values <: Tuple] <: Tuple = Values match
  case EmptyTuple   => EmptyTuple
  case head *: tail => Nullable[head] *: NullableTuple[tail]

type LeftJoinSchema[
    Left <: NamedTuple.AnyNamedTuple,
    Right <: NamedTuple.AnyNamedTuple
] = NamedTuple.NamedTuple[
  Tuple.Concat[NamedTuple.Names[Left], NamedTuple.Names[Right]],
  Tuple.Concat[NamedTuple.DropNames[Left], NullableTuple[NamedTuple.DropNames[Right]]]
]

type RightJoinSchema[
    Left <: NamedTuple.AnyNamedTuple,
    Right <: NamedTuple.AnyNamedTuple
] = NamedTuple.NamedTuple[
  Tuple.Concat[NamedTuple.Names[Left], NamedTuple.Names[Right]],
  Tuple.Concat[NullableTuple[NamedTuple.DropNames[Left]], NamedTuple.DropNames[Right]]
]

type RenameFieldNames[
    Names <: Tuple,
    From <: String,
    To <: String
] <: Tuple = Names match
  case EmptyTuple    => EmptyTuple
  case From *: names => To *: names
  case head *: names => head *: RenameFieldNames[names, From, To]

type RenamedSchema[
    S <: NamedTuple.AnyNamedTuple,
    From <: String,
    To <: String
] = NamedTuple.NamedTuple[
  RenameFieldNames[NamedTuple.Names[S], From, To],
  NamedTuple.DropNames[S]
]

type RenameRequestSources[Requests <: Tuple] <: Tuple = Requests match
  case EmptyTuple                      => EmptyTuple
  case RenameRequest[from, to] *: tail =>
    from *: RenameRequestSources[tail]

type RenameRequestTargets[Requests <: Tuple] <: Tuple = Requests match
  case EmptyTuple                      => EmptyTuple
  case RenameRequest[from, to] *: tail => to *: RenameRequestTargets[tail]

type RequestedRename[
    Name,
    Requests <: Tuple
] = Requests match
  case EmptyTuple                      => Name
  case RenameRequest[from, to] *: tail =>
    Name match
      case from => to
      case _    => RequestedRename[Name, tail]

type RenameManyNames[
    Names <: Tuple,
    Requests <: Tuple
] <: Tuple = Names match
  case EmptyTuple   => EmptyTuple
  case head *: tail =>
    RequestedRename[head, Requests] *: RenameManyNames[tail, Requests]

type RenamedManySchema[
    S <: NamedTuple.AnyNamedTuple,
    Requests <: Tuple
] = NamedTuple.NamedTuple[
  RenameManyNames[NamedTuple.Names[S], Requests],
  NamedTuple.DropNames[S]
]

type DroppedSchema[
    S <: NamedTuple.AnyNamedTuple,
    Name <: String
] = NamedTuple.NamedTuple[
  RemoveFieldNames[NamedTuple.Names[S], NamedTuple.DropNames[S], Name],
  RemoveFieldValues[NamedTuple.Names[S], NamedTuple.DropNames[S], Name]
]

type ReplaceFieldValues[
    Names <: Tuple,
    Values <: Tuple,
    Name <: String,
    Replacement
] <: Tuple = (Names, Values) match
  case (EmptyTuple, EmptyTuple)     => EmptyTuple
  case (Name *: names, _ *: values) => Replacement *: values
  case (_ *: names, head *: values) =>
    head *: ReplaceFieldValues[names, values, Name, Replacement]

type ReplacedSchema[
    S <: NamedTuple.AnyNamedTuple,
    Name <: String,
    Replacement
] = NamedTuple.NamedTuple[
  NamedTuple.Names[S],
  ReplaceFieldValues[
    NamedTuple.Names[S],
    NamedTuple.DropNames[S],
    Name,
    Replacement
  ]
]

type RenameTwoNames[
    Names <: Tuple,
    From1 <: String,
    To1 <: String,
    From2 <: String,
    To2 <: String
] <: Tuple = Names match
  case EmptyTuple    => EmptyTuple
  case From1 *: tail => To1 *: RenameTwoNames[tail, From1, To1, From2, To2]
  case From2 *: tail => To2 *: RenameTwoNames[tail, From1, To1, From2, To2]
  case head *: tail  => head *: RenameTwoNames[tail, From1, To1, From2, To2]

type RenamedTwoSchema[
    S <: NamedTuple.AnyNamedTuple,
    From1 <: String,
    To1 <: String,
    From2 <: String,
    To2 <: String
] = NamedTuple.NamedTuple[
  RenameTwoNames[NamedTuple.Names[S], From1, To1, From2, To2],
  NamedTuple.DropNames[S]
]

type DropManySchema[
    S <: NamedTuple.AnyNamedTuple,
    Names <: Tuple
] <: NamedTuple.AnyNamedTuple = Names match
  case EmptyTuple   => S
  case name *: tail => DropManySchema[DroppedSchema[S, name & String], tail]

type DropRequestNames[Requests <: Tuple] <: Tuple = Requests match
  case EmptyTuple                => EmptyTuple
  case DropRequest[name] *: tail => name *: DropRequestNames[tail]

type RemoveFieldNames[
    Names <: Tuple,
    Values <: Tuple,
    Name <: String
] <: Tuple = (Names, Values) match
  case (EmptyTuple, EmptyTuple)     => EmptyTuple
  case (Name *: names, _ *: values) => names
  case (head *: names, _ *: values) =>
    head *: RemoveFieldNames[names, values, Name]

type RemoveFieldValues[
    Names <: Tuple,
    Values <: Tuple,
    Name <: String
] <: Tuple = (Names, Values) match
  case (EmptyTuple, EmptyTuple)     => EmptyTuple
  case (Name *: names, _ *: values) => values
  case (_ *: names, head *: values) =>
    head *: RemoveFieldValues[names, values, Name]

type UsingJoinSchema[
    Left <: NamedTuple.AnyNamedTuple,
    Right <: NamedTuple.AnyNamedTuple,
    Name <: String
] = NamedTuple.NamedTuple[
  Tuple.Concat[
    NamedTuple.Names[Left],
    RemoveFieldNames[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name]
  ],
  Tuple.Concat[
    NamedTuple.DropNames[Left],
    RemoveFieldValues[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name]
  ]
]

type LeftUsingJoinSchema[
    Left <: NamedTuple.AnyNamedTuple,
    Right <: NamedTuple.AnyNamedTuple,
    Name <: String
] = NamedTuple.NamedTuple[
  Tuple.Concat[
    NamedTuple.Names[Left],
    RemoveFieldNames[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name]
  ],
  Tuple.Concat[
    NamedTuple.DropNames[Left],
    NullableTuple[
      RemoveFieldValues[NamedTuple.Names[Right], NamedTuple.DropNames[Right], Name]
    ]
  ]
]

type UsingKeyNames[Keys <: Tuple] <: Tuple = Keys match
  case EmptyTuple             => EmptyTuple
  case UsingKey[name] *: tail => name *: UsingKeyNames[tail]

type UsingJoinManySchema[
    Left <: NamedTuple.AnyNamedTuple,
    Right <: NamedTuple.AnyNamedTuple,
    Keys <: Tuple
] = ConcatSchema[Left, DropManySchema[Right, Keys]]

type NullableSchema[S <: NamedTuple.AnyNamedTuple] = NamedTuple.NamedTuple[
  NamedTuple.Names[S],
  NullableTuple[NamedTuple.DropNames[S]]
]

type LeftUsingJoinManySchema[
    Left <: NamedTuple.AnyNamedTuple,
    Right <: NamedTuple.AnyNamedTuple,
    Keys <: Tuple
] = ConcatSchema[Left, NullableSchema[DropManySchema[Right, Keys]]]

@implicitNotFound(
  "Rename requests ${Requests} must be a tuple of RenameRequest values whose source columns exist in ${Names}."
)
private[frame4s] trait RenameRequests[
    Names <: Tuple,
    Values <: Tuple,
    Requests <: Tuple
]:
  def pairs(requests: Requests): Vector[(String, String)]

private[frame4s] object RenameRequests:
  given [Names <: Tuple, Values <: Tuple]: RenameRequests[Names, Values, EmptyTuple] with
    def pairs(requests: EmptyTuple): Vector[(String, String)] = Vector.empty

  given cons[
      Names <: Tuple,
      Values <: Tuple,
      From <: String & Singleton,
      To <: String & Singleton,
      Tail <: Tuple
  ](using
      at: ColumnLookup[Names, Values, From],
      tail: RenameRequests[Names, Values, Tail]
  ): RenameRequests[Names, Values, RenameRequest[From, To] *: Tail] with
    def pairs(
        requests: RenameRequest[From, To] *: Tail
    ): Vector[(String, String)] =
      (requests.head.from, requests.head.to) +: tail.pairs(requests.tail)

@implicitNotFound(
  "Drop requests ${Requests} must be a tuple of DropRequest values whose columns exist in ${Names}."
)
private[frame4s] trait DropRequests[
    Names <: Tuple,
    Values <: Tuple,
    Requests <: Tuple
]:
  def names(requests: Requests): Vector[String]

private[frame4s] object DropRequests:
  given [Names <: Tuple, Values <: Tuple]: DropRequests[Names, Values, EmptyTuple] with
    def names(requests: EmptyTuple): Vector[String] = Vector.empty

  given cons[
      Names <: Tuple,
      Values <: Tuple,
      Name <: String & Singleton,
      Tail <: Tuple
  ](using
      at: ColumnLookup[Names, Values, Name],
      tail: DropRequests[Names, Values, Tail]
  ): DropRequests[Names, Values, DropRequest[Name] *: Tail] with
    def names(requests: DropRequest[Name] *: Tail): Vector[String] =
      requests.head.name +: tail.names(requests.tail)

@implicitNotFound(
  "Using join key '${Name}' has incompatible field types ${Left} and ${Right}. Both sides must use exactly the same typed-column representation."
)
sealed private[frame4s] trait SameJoinKeyType[
    Name <: String,
    Left,
    Right
]

private[frame4s] object SameJoinKeyType:
  given [Name <: String, Value]: SameJoinKeyType[Name, Value, Value] with {}

@implicitNotFound(
  "Using keys ${Keys} must be a tuple of UsingKey values present with identical types in both schemas."
)
private[frame4s] trait UsingKeys[
    LeftNames <: Tuple,
    LeftValues <: Tuple,
    RightNames <: Tuple,
    RightValues <: Tuple,
    Keys <: Tuple
]:
  def columns(keys: Keys): Vector[(String, Int, Int)]

private[frame4s] object UsingKeys:
  given [
      LeftNames <: Tuple,
      LeftValues <: Tuple,
      RightNames <: Tuple,
      RightValues <: Tuple
  ]: UsingKeys[LeftNames, LeftValues, RightNames, RightValues, EmptyTuple] with
    def columns(keys: EmptyTuple): Vector[(String, Int, Int)] = Vector.empty

  given cons[
      LeftNames <: Tuple,
      LeftValues <: Tuple,
      RightNames <: Tuple,
      RightValues <: Tuple,
      Name <: String & Singleton,
      Tail <: Tuple
  ](using
      leftAt: ColumnLookup[LeftNames, LeftValues, Name],
      rightAt: ColumnLookup[RightNames, RightValues, Name],
      same: SameJoinKeyType[
        Name,
        FieldType[LeftNames, LeftValues, Name],
        FieldType[RightNames, RightValues, Name]
      ],
      tail: UsingKeys[LeftNames, LeftValues, RightNames, RightValues, Tail]
  ): UsingKeys[
    LeftNames,
    LeftValues,
    RightNames,
    RightValues,
    UsingKey[Name] *: Tail
  ] with
    def columns(keys: UsingKey[Name] *: Tail): Vector[(String, Int, Int)] =
      (keys.head.name, leftAt.index, rightAt.index) +: tail.columns(keys.tail)

@implicitNotFound(
  "Join output column names overlap between ${LeftNames} and ${RightNames}. Rename or drop the duplicated fields before joining."
)
private[frame4s] trait DisjointNames[
    LeftNames <: Tuple,
    RightNames <: Tuple,
    RightValues <: Tuple
]

private[frame4s] object DisjointNames:
  final class Evidence[
      LeftNames <: Tuple,
      RightNames <: Tuple,
      RightValues <: Tuple
  ] extends DisjointNames[LeftNames, RightNames, RightValues]

  transparent inline given derived[
      LeftNames <: Tuple,
      RightNames <: Tuple,
      RightValues <: Tuple
  ]: DisjointNames[LeftNames, RightNames, RightValues] =
    check[LeftNames, RightNames]
    new Evidence

  private inline def check[
      LeftNames <: Tuple,
      RightNames <: Tuple
  ]: Unit =
    inline erasedValue[LeftNames] match
      case _: EmptyTuple     => ()
      case _: (head *: tail) =>
        inline erasedValue[ContainsName[RightNames, head & String]] match
          case _: true =>
            error(
              "Join output column '" + constValue[head & String] +
                "' occurs on both sides. Rename or drop it before joining."
            )
          case _: false => check[tail, RightNames]
