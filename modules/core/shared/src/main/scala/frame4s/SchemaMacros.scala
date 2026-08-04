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
              val field = Expr.summon[ColumnType[value]] match
                case Some(columnType) =>
                  '{
                    SchemaDescriptor.field[value](
                      ${ Expr(fieldName) },
                      $columnType
                    )
                  }
                case None =>
                  // Keep derivation available long enough for a nested ColumnLookup to emit its
                  // offending singleton name. Aborting this macro here makes the outer
                  // SchemaDescriptor failure mask the useful lookup diagnostic.
                  val message =
                    if TypeRepr.of[value].dealias.simplified =:= TypeRepr.of[Nothing]
                    then
                      s"Column '$fieldName' has no supported field type. Check the spelling or declare a supported frame4s column type."
                    else s"Schema field '$fieldName' has unsupported type ${Type.show[value]}."
                  '{ scala.compiletime.error(${ Expr(message) }) }
              field :: fields(TypeRepr.of[nameTail], TypeRepr.of[valueTail])
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
