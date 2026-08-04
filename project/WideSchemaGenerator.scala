import sbt._

object WideSchemaGenerator {
  def generate(outputRoot: File, width: Int): Seq[File] = {
    val digits = width.toString.length.max(3)
    def column(index: Int): String = "c" + s"%0${digits}d".format(index)

    val fields =
      (1 to width).map(index => s"      ${column(index)}: Int").mkString(",\n")
    val row =
      (1 to width).map(index => s"      ${column(index)} = $index").mkString(",\n")
    val first = column(1)
    val second = column(2)
    val middle = column(width / 2)
    val beforeLast = column(width - 1)
    val last = column(width)
    val objectName = s"WideSchema$width"
    val output = outputRoot / "consumer" / s"$objectName.scala"
    val diagnosticCourt =
      if (width == 32)
        """  private def verifyNegativeDiagnostics(): Unit =
          |    val missing = scala.compiletime.testing.typeCheckErrors(
          |      "import frame4s.*\ntype Left = (id: Int, group: Int, value: String)\ntype Right = (group: Int, id: Int, other: String)\nval left: Frame[Left] = ???\nval right: Frame[Right] = ???\nleft.innerJoinUsing(right, (UsingKey(\"id\"), UsingKey(\"missing\")))"
          |    )
          |    val duplicate = scala.compiletime.testing.typeCheckErrors(
          |      "import frame4s.*\ntype Left = (id: Int, group: Int, value: String)\ntype Right = (group: Int, id: Int, other: String)\nval left: Frame[Left] = ???\nval right: Frame[Right] = ???\nleft.innerJoinUsing(right, (UsingKey(\"id\"), UsingKey(\"id\")))"
          |    )
          |    val mistyped = scala.compiletime.testing.typeCheckErrors(
          |      "import frame4s.*\ntype Left = (id: Int, group: Int, value: String)\ntype Right = (group: String, id: Int, other: String)\nval left: Frame[Left] = ???\nval right: Frame[Right] = ???\nleft.innerJoinUsing(right, (UsingKey(\"id\"), UsingKey(\"group\")))"
          |    )
          |    requireCourt(
          |      missing.exists(error => error.message.contains("missing") && error.message.contains("does not exist")),
          |      "missing-key diagnostic changed"
          |    )
          |    requireCourt(
          |      duplicate.exists(error => error.message.contains("id") && error.message.contains("duplicated")),
          |      "duplicate-key diagnostic changed"
          |    )
          |    requireCourt(
          |      mistyped.exists(error => error.message.contains("group") && error.message.contains("Int") && error.message.contains("String")),
          |      "mistyped-key diagnostic changed"
          |    )
          |""".stripMargin
      else
        """  private def verifyNegativeDiagnostics(): Unit = ()
          |""".stripMargin
    val source =
      s"""package consumer
         |
         |import frame4s.*
         |
         |object $objectName:
         |  type Wide = (
         |$fields
         |  )
         |
         |  private def checked[A](result: Either[?, A]): A =
         |    result.fold(error => sys.error(error.toString), identity)
         |
         |  private def requireCourt(condition: Boolean, detail: String): Unit =
         |    if !condition then sys.error(detail)
         |
$diagnosticCourt
         |  private lazy val descriptor = summon[SchemaDescriptor[Wide]]
         |
         |  private def inputFrame: Frame[Wide] =
         |    val reference = checked(SourceRef.values("wide-$width", "wide-$width"))
         |    checked(Frame.values[Wide](reference)(using descriptor))
         |
         |  private def finalColumnNames(frame: Frame[Wide]) =
         |    frame.select(row => Tuple1(row.col("$last"))).schema.fields.map(_.name)
         |
         |  private def projectionWidth(frame: Frame[Wide]) =
         |    frame.select: row =>
         |      (row.col("$first"), row.col("$middle"), row.col("$last"))
         |    .schema.size
         |
         |  private def renamedWidth(frame: Frame[Wide]) =
         |    frame.renameAll(
         |      (
         |        RenameRequest("$middle", "renamedMiddle"),
         |        RenameRequest("$beforeLast", "renamedBeforeLast"),
         |        RenameRequest("$last", "renamedLast")
         |      )
         |    ).schema.size
         |
         |  private def droppedWidth(frame: Frame[Wide]) =
         |    frame.dropAll(
         |      (DropRequest("$middle"), DropRequest("$beforeLast"), DropRequest("$last"))
         |    ).schema.size
         |
         |  private def groupedWidth(frame: Frame[Wide]) =
         |    frame
         |      .groupBy(row => Tuple1(row.col("$last")))
         |      .aggregate(_ => Tuple1(Aggregate.count.as("rows")))
         |      .schema.size
         |
         |  private def joinedWidth(frame: Frame[Wide]) =
         |    val left = frame.select: row =>
         |      (row.col("$first"), row.col("$second"), row.col("$last").as("leftLast"))
         |    val right = frame.select: row =>
         |      (row.col("$second"), row.col("$first"), row.col("$middle").as("rightMiddle"))
         |    left
         |      .innerJoinUsing(right, (UsingKey("$first"), UsingKey("$second")))
         |      .schema.size
         |
         |  private def sortedWidth(frame: Frame[Wide]) =
         |    frame.sortBy(
         |      row => SortKey(row.col("$first")),
         |      row => SortKey(row.col("$last")).descending.nullsFirst
         |    ).schema.size
         |
         |  private def value: Wide = (
         |$row
         |    )
         |
         |  private def verifyRowRoundTrip(): Unit =
         |    given SchemaDescriptor[Wide] = descriptor
         |    val expected = value
         |    val table = checked(Table.fromRows[Wide](Vector(expected), batchSize = 1))
         |    try requireCourt(checked(table.row(0)) == expected, "row decoding changed")
         |    finally table.close()
         |
         |  def main(arguments: Array[String]): Unit =
         |    val frame = inputFrame
         |    requireCourt(descriptor.schema.size == $width, "descriptor width changed")
         |    requireCourt(finalColumnNames(frame) == Vector("$last"), "final lookup failed")
         |    requireCourt(projectionWidth(frame) == 3, "projection width changed")
         |    requireCourt(renamedWidth(frame) == $width, "atomic rename width changed")
         |    requireCourt(droppedWidth(frame) == ${width - 3}, "atomic drop width changed")
         |    requireCourt(groupedWidth(frame) == 2, "grouped schema changed")
         |    requireCourt(joinedWidth(frame) == 4, "multikey join schema changed")
         |    requireCourt(sortedWidth(frame) == $width, "sort schema changed")
         |    verifyRowRoundTrip()
         |    verifyNegativeDiagnostics()
         |    println("staged-wide-$width=ok")
         |""".stripMargin
    IO.write(output, source)
    Seq(output)
  }
}
