package frame4s

import scala.NamedTuple
import scala.quoted.*

private[frame4s] object RowCodecMacros:
  def rowCodec[S <: NamedTuple.AnyNamedTuple: Type](
      descriptor: Expr[SchemaDescriptor[S]]
  )(using Quotes): Expr[RowCodec[S]] =
    import quotes.reflect.*

    val valuesType = TypeRepr.of[NamedTuple.DropNames[S]].dealias.simplified

    def scalarCodec[A: Type](field: Int): Expr[ScalarCodec[A]] =
      Expr
        .summon[ScalarCodec[A]]
        .getOrElse:
          report.errorAndAbort(
            s"Schema field $field has unsupported row type ${Type.show[A]}."
          )

    trait Decoded:
      type Values <: Tuple
      given valuesType: Type[Values]
      def expression: Expr[Either[TableReadError, Values]]

    def decodedField[A: Type](
        codec: Expr[ScalarCodec[A]],
        table: Expr[Table[S]],
        batch: Expr[RecordBatch],
        batchRow: Expr[Int],
        logicalRow: Expr[Long],
        field: Int
    ): Decoded =
      new Decoded:
        type Values = A *: EmptyTuple
        given valuesType: Type[Values] = Type.of[A *: EmptyTuple]
        val expression = '{
          RowCodecSupport
            .decodeField[A](
              $codec,
              $table,
              $batch,
              $batchRow,
              $logicalRow,
              ${ Expr(field) }
            )
            .map(value => value *: EmptyTuple)
        }

    def decodedFields(
        current: TypeRepr,
        table: Expr[Table[S]],
        batch: Expr[RecordBatch],
        batchRow: Expr[Int],
        logicalRow: Expr[Long],
        field: Int
    ): List[Decoded] =
      current.dealias.simplified.asType match
        case '[EmptyTuple]   => Nil
        case '[head *: tail] =>
          val codec = scalarCodec[head](field)
          decodedField[head](
            codec,
            table,
            batch,
            batchRow,
            logicalRow,
            field
          ) :: decodedFields(
            TypeRepr.of[tail],
            table,
            batch,
            batchRow,
            logicalRow,
            field + 1
          )

    def combine(left: Decoded, right: Decoded): Decoded =
      given leftValuesType: Type[left.Values] = left.valuesType
      given rightValuesType: Type[right.Values] = right.valuesType
      val leftExpression = left.expression
      val rightExpression = right.expression
      new Decoded:
        type Values = Tuple.Concat[left.Values, right.Values]
        given valuesType: Type[Values] =
          Type.of[Tuple.Concat[left.Values, right.Values]]
        val expression = '{
          $leftExpression.flatMap: leftValues =>
            $rightExpression.map: rightValues =>
              leftValues ++ rightValues
        }

    def balance(fields: List[Decoded]): Decoded =
      fields match
        case value :: Nil => value
        case values       =>
          val next = values
            .grouped(2)
            .map:
              case left :: right :: Nil => combine(left, right)
              case value :: Nil         => value
              case _                    =>
                report.errorAndAbort("Internal row codec balancing error.")
          balance(next.toList)

    def decodedTuple[Values <: Tuple: Type](
        table: Expr[Table[S]],
        batch: Expr[RecordBatch],
        batchRow: Expr[Int],
        logicalRow: Expr[Long]
    ): Expr[Either[TableReadError, Values]] =
      val fields = decodedFields(
        TypeRepr.of[Values],
        table,
        batch,
        batchRow,
        logicalRow,
        0
      )
      fields match
        case Nil =>
          '{ Right[TableReadError, EmptyTuple](EmptyTuple) }
            .asExprOf[Either[TableReadError, Values]]
        case _ =>
          val decoded = balance(fields)
          decoded.expression.asExprOf[Either[TableReadError, Values]]

    def encodedFields[Values <: Tuple: Type](
        values: Expr[Values],
        current: TypeRepr,
        field: Int
    ): List[Expr[Either[RowEncodingError, ScalarValue]]] =
      current.dealias.simplified.asType match
        case '[EmptyTuple]   => Nil
        case '[head *: tail] =>
          val codec = scalarCodec[head](field)
          val value =
            '{ $values.apply(${ Expr(field) }) }.asExprOf[head]
          '{
            RowCodecSupport.encodeField[head](
              $codec,
              $value,
              ${ Expr(field) }
            )
          } :: encodedFields(values, TypeRepr.of[tail], field + 1)

    val decoder = valuesType.asType match
      case '[EmptyTuple] =>
        '{
          (
              table: Table[S],
              batch: RecordBatch,
              batchRow: Int,
              logicalRow: Long
          ) =>
            ${
              val expression = decodedTuple[EmptyTuple](
                'table,
                'batch,
                'batchRow,
                'logicalRow
              )
              '{
                $expression.map(values => RowCodecSupport.namedTuple[S, EmptyTuple](values))
              }
            }
        }
      case '[head *: tail] =>
        '{
          (
              table: Table[S],
              batch: RecordBatch,
              batchRow: Int,
              logicalRow: Long
          ) =>
            ${
              val expression = decodedTuple[head *: tail](
                'table,
                'batch,
                'batchRow,
                'logicalRow
              )
              '{
                $expression.map(values => RowCodecSupport.namedTuple[S, head *: tail](values))
              }
            }
        }

    val encoder = valuesType.asType match
      case '[EmptyTuple] =>
        '{ (_: S) => Right(Vector.empty[ScalarValue]) }
      case '[head *: tail] =>
        '{ (row: S) =>
          val values =
            RowCodecSupport.valuesTuple[S, head *: tail](row)
          ${
            val parts = Expr.ofSeq(
              encodedFields[head *: tail](
                'values,
                TypeRepr.of[head *: tail],
                0
              )
            )
            '{ RowCodecSupport.sequence($parts) }
          }
        }

    '{
      new RowCodec.Evidence[S](
        $descriptor.schema,
        $decoder,
        $encoder
      )
    }
