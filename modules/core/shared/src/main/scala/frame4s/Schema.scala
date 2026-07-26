package frame4s

import scala.NamedTuple
import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, erasedValue, error}

opaque type ColumnId = String

object ColumnId:
  private[frame4s] def derived(name: String): ColumnId = s"column:$name"

  extension (id: ColumnId) def value: String = id

opaque type ExprId = String

object ExprId:
  private[frame4s] def derived(description: String): ExprId = s"expr:$description"

  extension (id: ExprId) def value: String = id

opaque type SourceId = String

object SourceId:
  private[frame4s] def unsafe(value: String): SourceId = value

  extension (id: SourceId) def value: String = id

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
  case NullFieldName(index: Int)
  case EmptyFieldName(index: Int)
  case DuplicateFieldName(name: String)

  def message: String = this match
    case NullFieldName(index)     => s"field at index $index has a null name"
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
    // The null check must precede `trim`: a Field can reach here carrying a null name from any
    // caller of DynamicFrame.field, including foreign-format decoders (Arrow leaves the field
    // name unset for an unnamed column).
    fields.zipWithIndex.collectFirst {
      case (field, index) if field.name == null      => SchemaError.NullFieldName(index)
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
trait ColumnType[A]:
  def dataType: DataType
  def nullable: Boolean
  private[frame4s] def literal(value: A): LiteralValue

object ColumnType:
  given ColumnType[Boolean] with
    val dataType = DataType.Bool
    val nullable = false
    private[frame4s] def literal(value: Boolean) = LiteralValue.Bool(value)

  given ColumnType[Int] with
    val dataType = DataType.Int32
    val nullable = false
    private[frame4s] def literal(value: Int) = LiteralValue.Int32(value)

  given ColumnType[Long] with
    val dataType = DataType.Int64
    val nullable = false
    private[frame4s] def literal(value: Long) = LiteralValue.Int64(value)

  given ColumnType[Float] with
    val dataType = DataType.Float32
    val nullable = false
    private[frame4s] def literal(value: Float) = LiteralValue.Float32(value)

  given ColumnType[Double] with
    val dataType = DataType.Float64
    val nullable = false
    private[frame4s] def literal(value: Double) = LiteralValue.Float64(value)

  given ColumnType[String] with
    val dataType = DataType.Utf8
    val nullable = false
    private[frame4s] def literal(value: String) = LiteralValue.Utf8(value)

  given ColumnType[TimestampMicros] with
    val dataType = DataType.Timestamp(TimeUnit.Microsecond)
    val nullable = false
    private[frame4s] def literal(value: TimestampMicros) =
      LiteralValue.Timestamp(value.toLong, TimeUnit.Microsecond)

  given [A](using value: ColumnType[A]): ColumnType[Option[A]] with
    val dataType = value.dataType
    val nullable = true
    private[frame4s] def literal(input: Option[A]) = input match
      case Some(actual) => value.literal(actual)
      case None         => LiteralValue.Null(value.dataType)

private[frame4s] trait SchemaFields[Names <: Tuple, Values <: Tuple]:
  def fields: Vector[Field]

private[frame4s] object SchemaFields:
  given SchemaFields[EmptyTuple, EmptyTuple] with
    val fields = Vector.empty

  given [
      Name <: String & Singleton,
      Names <: Tuple,
      Value,
      Values <: Tuple
  ](using
      name: ValueOf[Name],
      value: ColumnType[Value],
      tail: SchemaFields[Names, Values]
  ): SchemaFields[Name *: Names, Value *: Values] with
    val fields = Field(
      ColumnId.derived(name.value),
      name.value,
      value.dataType,
      value.nullable
    ) +: tail.fields

/** Derives the runtime [[Schema]] for a named-tuple type.
  *
  * Sealed on purpose: every typed combinator reads `schema` while the compiler reasons about `S`,
  * so an externally supplied instance could hand a `Frame[S]` a runtime schema unrelated to `S`.
  * `schema` stays publicly readable; only implementation is restricted.
  */
sealed trait SchemaDescriptor[S <: NamedTuple.AnyNamedTuple]:
  def schema: Schema

object SchemaDescriptor:
  /** Derive the runtime schema for a named-tuple type. */
  given derived[S <: NamedTuple.AnyNamedTuple](using
      fields: SchemaFields[NamedTuple.Names[S], NamedTuple.DropNames[S]]
  ): SchemaDescriptor[S] with
    val schema = Schema.unsafe(fields.fields)

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
    new Evidence(indexOf[Names, Values, Name])

  private inline def indexOf[
      Names <: Tuple,
      Values <: Tuple,
      Name <: String
  ]: Int =
    inline erasedValue[(Names, Values)] match
      case _: ((Name *: names), (value *: values)) => 0
      case _: ((head *: names), (value *: values)) =>
        1 + indexOf[names, values, Name]
      case _: (EmptyTuple, EmptyTuple) =>
        error(
          "Column '" + constValue[Name] +
            "' does not exist in this schema. Check the spelling or project the column before this operation."
        )

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

private[frame4s] trait DisjointNames[
    LeftNames <: Tuple,
    RightNames <: Tuple,
    RightValues <: Tuple
]

private[frame4s] object DisjointNames:
  given empty[RightNames <: Tuple, RightValues <: Tuple]
      : DisjointNames[EmptyTuple, RightNames, RightValues] with {}

  given [
      Head <: String,
      Tail <: Tuple,
      RightNames <: Tuple,
      RightValues <: Tuple
  ](using
      absent: scala.util.NotGiven[ColumnAt[RightNames, RightValues, Head]],
      next: DisjointNames[Tail, RightNames, RightValues]
  ): DisjointNames[Head *: Tail, RightNames, RightValues] with {}
