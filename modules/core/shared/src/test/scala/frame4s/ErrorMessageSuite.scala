package frame4s

class ErrorMessageSuite extends munit.FunSuite:
  private val expectedSchema =
    Schema.unsafe(Vector(DynamicFrame.field("expected", DataType.Int32, false)))
  private val actualSchema =
    Schema.unsafe(Vector(DynamicFrame.field("actual", DataType.Utf8, true)))
  private val expressionId = ExprId.derived("coverage")
  private val sourceId = SourceId.unsafe("coverage")

  test("schema and binding diagnostics cover every public branch"):
    val schemaErrors = Vector(
      SchemaError.NullFieldName(1),
      SchemaError.EmptyFieldName(2),
      SchemaError.DuplicateFieldName("id")
    )
    val bindingIssues = Vector(
      BindingIssue.FieldCount(2, 1),
      BindingIssue.FieldName(0, "expected", "actual"),
      BindingIssue.FieldType(0, DataType.Int32, DataType.Utf8),
      BindingIssue.FieldNullability(0, false, true)
    )

    assert(schemaErrors.forall(_.message.nonEmpty))
    assert(bindingIssues.forall(_.message.nonEmpty))
    assertEquals(
      bindingIssues.map(_.message),
      Vector(
        "expected 2 fields but found 1",
        "field 0 is named 'actual'; expected 'expected'",
        "field 0 has type Utf8; expected Int32",
        "field 0 nullable=true; expected nullable=false"
      )
    )

  test("frame planning diagnostics cover every public branch"):
    val errors = Vector(
      FrameError.NullSourceId,
      FrameError.NullSourceName,
      FrameError.InvalidSourceId(""),
      FrameError.InvalidSourceName(""),
      FrameError.InvalidSchema(SchemaError.EmptyFieldName(0)),
      FrameError.SchemaMismatch(Vector(BindingIssue.FieldCount(2, 1))),
      FrameError.ColumnNotFound("missing"),
      FrameError.ColumnCollision("id"),
      FrameError.DuplicateOutputNames(Vector("id", "score")),
      FrameError.DuplicateColumnRequests(Vector("id", "score")),
      FrameError.InvalidColumnReference("id", 2),
      FrameError.ExpressionType(DataType.Int32, DataType.Utf8),
      FrameError.NullablePredicate(expressionId),
      FrameError.InvalidExpressionScope(expressionId),
      FrameError.InvalidLimit(-1),
      FrameError.NotValuesSource(sourceId, SourceKind.Scan),
      FrameError.EmptySort,
      FrameError.EmptyJoinKeys,
      FrameError.DuplicateJoinKey("id"),
      FrameError.JoinKeyType("id", DataType.Int32, DataType.Int64)
    )

    assert(errors.forall(_.message.nonEmpty))
    assertEquals(FrameError.NullSourceId.message, "source id is null")
    assertEquals(FrameError.NullSourceName.message, "source name is null")
    assertEquals(
      FrameError.DuplicateOutputNames(Vector("id", "score")).message,
      "output column names are duplicated: id, score"
    )
    assertEquals(
      FrameError.InvalidExpressionScope(expressionId).message,
      "expression expr:coverage references a different input scope"
    )

  test("storage diagnostics cover every public branch"):
    val errors = Vector(
      StorageError.BufferClosed,
      StorageError.InvalidRange(2, 4, 5),
      StorageError.InvalidValidityLength(2, 1),
      StorageError.ColumnLengthMismatch(2, 1, 0),
      StorageError.ColumnCountMismatch(2, 1),
      StorageError.ColumnTypeMismatch(0, DataType.Int32, DataType.Utf8),
      StorageError.RequiredColumnContainsNull(0, 1),
      StorageError.SchemaMismatch(expectedSchema, actualSchema),
      StorageError.ColumnNotFound("missing"),
      StorageError.NullValue(2),
      StorageError.InvalidUtf8Offsets(1, 3, 2, 4),
      StorageError.InvalidDictionaryIndex(1, 4, 3),
      StorageError.DictionaryContainsNull(1),
      StorageError.SourceAlreadyOpened,
      StorageError.SourceClosed,
      StorageError.Unexpected("unexpected")
    )

    assert(errors.forall(_.message.nonEmpty))
    assertEquals(
      StorageError.InvalidDictionaryIndex(1, 4, 3).message,
      "dictionary index at 1 is 4; dictionary size is 3"
    )

  test("execution diagnostics cover every public branch"):
    val errors = Vector(
      ExecutionError.Storage(StorageError.BufferClosed),
      ExecutionError.MissingSource(sourceId),
      ExecutionError.SourceSchema(expectedSchema, actualSchema),
      ExecutionError.InvalidColumnIndex(expressionId, "left", 2, 1),
      ExecutionError.ExpressionType(expressionId, DataType.Int32, ScalarValue.Utf8("x")),
      ExecutionError.IncompatibleValues(
        expressionId,
        ScalarValue.Int32(1),
        ScalarValue.Utf8("x")
      ),
      ExecutionError.PredicateType(expressionId, ScalarValue.Int32(1)),
      ExecutionError.IntegerOverflow(expressionId, BinaryOperator.Add),
      ExecutionError.DivisionByZero(expressionId),
      ExecutionError.UnsupportedNode("window"),
      ExecutionError.InvalidLiteral(LiteralValue.Null(DataType.Int32))
    )

    assert(errors.forall(_.message.nonEmpty))
    assertEquals(
      ExecutionError.IntegerOverflow(expressionId, BinaryOperator.Add).message,
      "integer overflow in Add for expression expr:coverage"
    )

  test("table-read diagnostics cover every public branch"):
    val errors = Vector(
      TableReadError.Closed,
      TableReadError.RowOutOfBounds(2, 1),
      TableReadError.SchemaMismatch(expectedSchema, actualSchema),
      TableReadError.InvalidBatchSize(0),
      TableReadError.ScalarDecode(
        1,
        0,
        "id",
        ScalarValue.Null,
        DataType.Int32,
        nullable = false
      ),
      TableReadError.ScalarDecode(
        1,
        0,
        "id",
        ScalarValue.Utf8("x"),
        DataType.Int32,
        nullable = true
      ),
      TableReadError.InvalidRenderOptions("maxRows must be non-negative"),
      TableReadError.Storage(StorageError.BufferClosed)
    )

    assert(errors.forall(_.message.nonEmpty))
    assertEquals(
      errors(4).message,
      "row 1 column 0 ('id') value Null cannot be decoded as Int32"
    )
    assertEquals(
      errors(5).message,
      "row 1 column 0 ('id') value Utf8(x) cannot be decoded as Int32 or null"
    )
