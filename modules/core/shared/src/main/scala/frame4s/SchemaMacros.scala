package frame4s

import scala.NamedTuple
import scala.quoted.*

private[frame4s] object SchemaMacros:
  def schemaDescriptor[
      S <: NamedTuple.AnyNamedTuple: Type
  ](using Quotes): Expr[SchemaDescriptor[S]] =
    import quotes.reflect.*

    def fields(
        names: TypeRepr,
        values: TypeRepr
    ): List[Expr[Field]] =
      names.dealias.simplified.asType match
        case '[EmptyTuple] =>
          values.dealias.simplified.asType match
            case '[EmptyTuple] => Nil
            case _             =>
              report.errorAndAbort(
                "Internal schema derivation error: field names and values have different lengths."
              )
        case '[name *: nameTail] =>
          values.dealias.simplified.asType match
            case '[value *: valueTail] =>
              val fieldName = TypeRepr.of[name].dealias.simplified match
                case ConstantType(StringConstant(actual)) => actual
                case other                                =>
                  report.errorAndAbort(
                    s"Internal schema derivation error: ${other.show} is not a singleton field name."
                  )
              val columnType = Expr
                .summon[ColumnType[value]]
                .getOrElse:
                  report.errorAndAbort(
                    s"Schema field '$fieldName' has unsupported type ${Type.show[value]}."
                  )
              '{
                SchemaDescriptor.field[value](
                  ${ Expr(fieldName) },
                  $columnType
                )
              } :: fields(TypeRepr.of[nameTail], TypeRepr.of[valueTail])
            case _ =>
              report.errorAndAbort(
                "Internal schema derivation error: field names and values have different lengths."
              )

    val generatedFields = Expr.ofSeq(
      fields(
        TypeRepr.of[NamedTuple.Names[S]],
        TypeRepr.of[NamedTuple.DropNames[S]]
      )
    )
    '{
      new SchemaDescriptor.Evidence[S](
        Schema.unsafe($generatedFields.toVector)
      )
    }

  def columnLookup[
      Names <: Tuple: Type,
      Values <: Tuple: Type,
      Name <: String: Type
  ](using Quotes): Expr[ColumnLookup[Names, Values, Name]] =
    import quotes.reflect.*

    val target = TypeRepr.of[Name].dealias.simplified

    def find(current: TypeRepr, index: Int): Option[Int] =
      current.dealias.simplified.asType match
        case '[EmptyTuple]   => None
        case '[head *: tail] =>
          if TypeRepr.of[head].dealias.simplified =:= target then Some(index)
          else find(TypeRepr.of[tail], index + 1)

    find(TypeRepr.of[Names], 0) match
      case Some(index) =>
        '{ new ColumnLookup.Evidence[Names, Values, Name](${ Expr(index) }) }
      case None =>
        '{
          scala.compiletime.error(
            "Column '" + scala.compiletime.constValue[Name] +
              "' does not exist in this schema. Check the spelling or project the column before this operation."
          )
        }
