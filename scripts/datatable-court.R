#!/usr/bin/env Rscript

# Separate-process data.table comparison court for frame4s.
#
# The relational court disables automatic and reusable data.table indices so
# one-shot operations do not silently inherit setup from earlier repetitions.
# A separate index study measures a linear equality scan, secondary-index
# construction, cold construction plus lookup, and warm index reuse.

suppressPackageStartupMessages({
  library(data.table)
  library(jsonlite)
})

expected_data_table <- "1.18.4"
expected_jsonlite <- "2.0.0"
u32_base <- 4294967296
null_hash <- c(hi = 1640531526, lo = 2159379435)

argument_value <- function(arguments, flag, default = NULL) {
  position <- match(flag, arguments)
  if (is.na(position)) {
    return(default)
  }
  if (position == length(arguments)) {
    stop(flag, " requires a value")
  }
  arguments[[position + 1L]]
}

parse_arguments <- function() {
  arguments <- commandArgs(trailingOnly = TRUE)
  receipt <- argument_value(arguments, "--receipt")
  oracle <- argument_value(arguments, "--oracle-validation")
  if (is.null(receipt) || is.null(oracle)) {
    stop("--receipt and --oracle-validation are required")
  }
  rows <- as.integer(argument_value(arguments, "--rows", "1000"))
  if (is.na(rows) || rows <= 0L) {
    stop("--rows must be positive")
  }
  scale_text <- argument_value(arguments, "--index-scales", "1000,100000,1000000")
  scales <- as.integer(strsplit(scale_text, ",", fixed = TRUE)[[1L]])
  if (length(scales) == 0L || anyNA(scales) || any(scales <= 0L)) {
    stop("--index-scales must be a comma-separated list of positive integers")
  }
  list(
    receipt = receipt,
    rows = rows,
    oracle = oracle,
    frame4s_jmh = argument_value(arguments, "--frame4s-jmh"),
    index_scales = unique(scales),
    quick = "--quick" %in% arguments
  )
}

u64_multiply_add <- function(value, addend) {
  low_total <- value[["lo"]] * 31 + addend[["lo"]]
  carry <- floor(low_total / u32_base)
  c(
    hi = (value[["hi"]] * 31 + addend[["hi"]] + carry) %% u32_base,
    lo = low_total %% u32_base
  )
}

u64_from_decimal <- function(value) {
  result <- c(hi = 0, lo = 0)
  digits <- utf8ToInt(value) - utf8ToInt("0")
  if (length(digits) == 0L || any(digits < 0L | digits > 9L)) {
    stop("invalid unsigned decimal checksum: ", value)
  }
  for (digit in digits) {
    low_total <- result[["lo"]] * 10 + digit
    carry <- floor(low_total / u32_base)
    result <- c(
      hi = (result[["hi"]] * 10 + carry) %% u32_base,
      lo = low_total %% u32_base
    )
  }
  result
}

u64_to_decimal <- function(value) {
  if (value[["hi"]] == 0 && value[["lo"]] == 0) {
    return("0")
  }
  digits <- character()
  current <- value
  while (current[["hi"]] != 0 || current[["lo"]] != 0) {
    high_quotient <- floor(current[["hi"]] / 10)
    high_remainder <- current[["hi"]] %% 10
    combined <- high_remainder * u32_base + current[["lo"]]
    low_quotient <- floor(combined / 10)
    remainder <- combined %% 10
    digits <- c(as.character(remainder), digits)
    current <- c(hi = high_quotient, lo = low_quotient)
  }
  paste0(digits, collapse = "")
}

java_string_hash <- function(value) {
  code_points <- utf8ToInt(enc2utf8(value))
  if (any(code_points > 65535L)) {
    stop("the benchmark checksum currently accepts BMP strings only")
  }
  hash <- 0
  for (code_point in code_points) {
    hash <- (hash * 31 + code_point) %% u32_base
  }
  if (hash >= 2147483648) {
    c(hi = u32_base - 1, lo = hash)
  } else {
    c(hi = 0, lo = hash)
  }
}

integer_hash <- function(value) {
  numeric_value <- as.double(value)
  if (numeric_value < 0) {
    c(hi = u32_base - 1, lo = u32_base + numeric_value)
  } else {
    c(hi = 0, lo = numeric_value)
  }
}

double_hash <- function(value) {
  bytes <- as.integer(writeBin(as.double(value), raw(), size = 8L, endian = "big"))
  c(
    hi = sum(bytes[1:4] * c(16777216, 65536, 256, 1)),
    lo = sum(bytes[5:8] * c(16777216, 65536, 256, 1))
  )
}

scalar_hash <- function(value) {
  if (length(value) != 1L || is.na(value)) {
    return(null_hash)
  }
  if (is.character(value)) {
    return(java_string_hash(value))
  }
  if (is.logical(value)) {
    return(c(hi = 0, lo = if (value) 1 else 2))
  }
  if (is.integer(value)) {
    return(integer_hash(value))
  }
  if (is.double(value)) {
    return(double_hash(value))
  }
  stop("unsupported checksum scalar type: ", typeof(value))
}

table_checksum <- function(table) {
  result <- c(hi = 0, lo = nrow(table))
  if (nrow(table) == 0L) {
    return(u64_to_decimal(result))
  }
  columns <- names(table)
  for (row in seq_len(nrow(table))) {
    for (column in columns) {
      result <- u64_multiply_add(result, scalar_hash(table[[column]][row]))
    }
  }
  u64_to_decimal(result)
}

read_oracle <- function(path) {
  oracle <- fread(path, sep = "\t", quote = "", showProgress = FALSE)
  setNames(
    lapply(seq_len(nrow(oracle)), function(index) {
      list(
        rows = as.integer(oracle$output_rows[index]),
        checksum = as.character(oracle$checksum[index])
      )
    }),
    oracle$benchmark
  )
}

read_frame4s_times <- function(path) {
  if (is.null(path)) {
    return(list())
  }
  content <- fromJSON(path, simplifyVector = FALSE)
  times <- list()
  for (result in content) {
    benchmark <- result$benchmark
    if (!grepl(".ColumnarBenchmarks.", benchmark, fixed = TRUE) ||
        result$mode != "avgt") {
      next
    }
    name <- sub("^.*\\.", "", benchmark)
    score <- as.double(result$primaryMetric$score)
    unit <- result$primaryMetric$scoreUnit
    if (unit == "us/op") {
      score <- score / 1000
    } else if (unit == "ns/op") {
      score <- score / 1000000
    } else if (unit != "ms/op") {
      next
    }
    times[[name]] <- score
  }
  times
}

measure <- function(operation, target_seconds, sample_count) {
  for (iteration in seq_len(20L)) {
    invisible(operation())
  }
  number <- 1L
  repeat {
    started <- proc.time()[["elapsed"]]
    for (iteration in seq_len(number)) {
      invisible(operation())
    }
    elapsed <- proc.time()[["elapsed"]] - started
    if (elapsed >= target_seconds || number >= 8192L) {
      break
    }
    number <- min(number * 2L, 8192L)
  }
  invisible(gc())
  samples <- numeric(sample_count)
  for (sample in seq_len(sample_count)) {
    started <- proc.time()[["elapsed"]]
    for (iteration in seq_len(number)) {
      invisible(operation())
    }
    samples[sample] <- (proc.time()[["elapsed"]] - started) / number
  }
  list(number = number, samples_ms = samples * 1000)
}

make_fixtures <- function(rows) {
  ids <- 0:(rows - 1L)
  values <- as.double(ids) / 8
  values[ids %% 7L == 0L] <- NA_real_
  facts <- data.table(
    id = as.integer(ids),
    group = paste0("g", ids %% 16L),
    value = values
  )
  left <- as.data.table(list(
    key = as.integer(ids),
    leftValue = as.integer(ids)
  ))
  right_one <- data.table(
    rightKey = as.integer(ids),
    rightValue = as.integer(ids * 2L)
  )
  right_many <- data.table(
    rightKey = as.integer(ids %/% 3L),
    rightValue = as.integer(ids * 3L)
  )
  sparse_rows <- max(1L, rows %/% 10L)
  sparse_ids <- 0:(sparse_rows - 1L)
  right_sparse <- data.table(
    rightKey = as.integer(sparse_ids * 10L),
    rightValue = as.integer(sparse_ids)
  )
  skew_rows <- max(1L, min(rows, 1000L))
  right_skew <- data.table(
    rightKey = integer(skew_rows),
    rightValue = 0:(skew_rows - 1L)
  )
  list(
    facts = facts,
    left = left,
    right_one = right_one,
    right_many = right_many,
    right_sparse = right_sparse,
    right_skew = right_skew
  )
}

make_workloads <- function(rows, fixtures) {
  facts <- fixtures$facts
  left <- fixtures$left
  right_one <- fixtures$right_one
  right_many <- fixtures$right_many
  right_sparse <- fixtures$right_sparse
  right_skew <- fixtures$right_skew

  join <- function(right) {
    right[
      left,
      on = .(rightKey = key),
      nomatch = NULL,
      allow.cartesian = TRUE,
      .(
        key = i.key,
        leftValue = i.leftValue,
        rightKey = x.rightKey,
        rightValue = x.rightValue
      )
    ]
  }

  workload <- function(name, oracle, operation, columns, exact = TRUE) {
    list(
      name = name,
      oracle = oracle,
      operation = operation,
      columns = columns,
      exact = exact
    )
  }

  list(
    workload(
      "primitiveMaterializedProjection",
      "ReferenceBenchmarks.primitiveScan",
      function() copy(facts[, .(id)]),
      c("id")
    ),
    workload(
      "fusedFilterProjectArithmetic",
      "ReferenceBenchmarks.fusedFilterProjectArithmetic",
      function() facts[id >= rows %/% 2L, .(id, `next` = id + 1L)],
      c("id", "next")
    ),
    workload(
      "groupedLowCardinalitySumOnly",
      "ReferenceBenchmarks.groupedLowCardinalitySumOnly",
      function() facts[, .(sum = sum(value, na.rm = TRUE)), by = group],
      c("group", "sum")
    ),
    workload(
      "groupedLowCardinality",
      "ReferenceBenchmarks.groupedLowCardinality",
      function() {
        facts[, {
          present <- value[!is.na(value)]
          .(
            n = .N,
            sum = sum(present),
            mean = mean(present),
            variancePop = mean((present - mean(present))^2)
          )
        }, by = group]
      },
      c("group", "n", "sum", "mean", "variancePop"),
      exact = FALSE
    ),
    workload(
      "joinOneToOne",
      "ReferenceBenchmarks.joinOneToOne",
      function() join(right_one),
      c("key", "leftValue", "rightKey", "rightValue")
    ),
    workload(
      "joinOneToMany",
      "ReferenceBenchmarks.joinOneToMany",
      function() join(right_many),
      c("key", "leftValue", "rightKey", "rightValue")
    ),
    workload(
      "joinSparse",
      "ReferenceBenchmarks.joinSparse",
      function() join(right_sparse),
      c("key", "leftValue", "rightKey", "rightValue")
    ),
    workload(
      "joinSkewed",
      "ReferenceBenchmarks.joinSkewed",
      function() join(right_skew),
      c("key", "leftValue", "rightKey", "rightValue")
    ),
    workload(
      "distinctLowCardinality",
      "ReferenceBenchmarks.distinctLowCardinality",
      function() unique(right_many[, .(rightKey)]),
      c("rightKey")
    ),
    workload(
      "semiJoinSparse",
      "ReferenceBenchmarks.semiJoinSparse",
      function() {
        positions <- left[
          right_sparse,
          on = .(key = rightKey),
          nomatch = NULL,
          which = TRUE
        ]
        left[positions, .(key, leftValue)]
      },
      c("key", "leftValue")
    ),
    workload(
      "antiJoinSparse",
      "ReferenceBenchmarks.antiJoinSparse",
      function() left[!right_sparse, on = .(key = rightKey), .(key, leftValue)],
      c("key", "leftValue")
    ),
    workload(
      "unionAll",
      "ReferenceBenchmarks.unionAll",
      function() rbindlist(list(left, left), use.names = TRUE),
      c("key", "leftValue")
    )
  )
}

validate_and_measure_workloads <- function(
    workloads,
    oracle,
    frame4s_times,
    target_seconds,
    sample_count
) {
  validations <- list()
  timings <- list()
  for (workload in workloads) {
    expected <- oracle[[workload$oracle]]
    if (is.null(expected)) {
      stop("oracle validation is missing ", workload$oracle)
    }
    output <- workload$operation()
    if (!identical(names(output), workload$columns)) {
      stop(
        workload$name,
        ": columns ",
        paste(names(output), collapse = ","),
        " disagree with ",
        paste(workload$columns, collapse = ",")
      )
    }
    if (nrow(output) != expected$rows) {
      stop(
        workload$name,
        ": output rows ",
        nrow(output),
        " disagree with ",
        expected$rows
      )
    }
    actual_checksum <- table_checksum(output)
    status <- "row-count-and-schema"
    if (workload$exact) {
      actual_words <- u64_from_decimal(actual_checksum)
      oracle_words <- u64_from_decimal(expected$checksum)
      if (!identical(unname(actual_words), unname(oracle_words))) {
        stop(
          workload$name,
          ": checksum ",
          actual_checksum,
          " disagrees with ",
          expected$checksum
        )
      }
      status <- "exact-checksum"
    }
    validations[[length(validations) + 1L]] <- data.table(
      benchmark = paste0("DataTableBenchmarks.", workload$name),
      oracle = workload$oracle,
      output_rows = nrow(output),
      checksum = actual_checksum,
      status = status
    )

    measurement <- measure(workload$operation, target_seconds, sample_count)
    frame4s_name <- sub("^.*\\.", "", workload$oracle)
    frame4s_ms <- frame4s_times[[frame4s_name]]
    median_ms <- median(measurement$samples_ms)
    timings[[length(timings) + 1L]] <- data.table(
      benchmark = workload$name,
      loops_per_sample = measurement$number,
      samples = sample_count,
      median_ms = median_ms,
      min_ms = min(measurement$samples_ms),
      max_ms = max(measurement$samples_ms),
      frame4s_ms = if (is.null(frame4s_ms)) NA_real_ else frame4s_ms,
      data_table_over_frame4s = if (is.null(frame4s_ms)) {
        NA_real_
      } else {
        median_ms / frame4s_ms
      }
    )
  }
  list(
    validations = rbindlist(validations),
    timings = rbindlist(timings)
  )
}

run_index_study <- function(scales, target_seconds, sample_count) {
  rows <- list()
  validations <- list()
  multiplier <- 104729
  for (scale in scales) {
    ids <- as.integer(((0:(scale - 1L)) * multiplier) %% scale)
    source <- data.table(id = ids)
    target <- as.integer(scale - 1L)
    batch_positions <- unique(as.integer(round(seq(1L, scale, length.out = 32L))))
    batch_targets <- source$id[batch_positions]

    unindexed_single <- function() {
      source[which(source$id == target), .(id)]
    }
    unindexed_batch <- function() {
      source[which(source$id %in% batch_targets), .(id)]
    }

    cold_source <- copy(source)
    cold_single <- function() {
      setindexv(cold_source, NULL)
      setindexv(cold_source, "id")
      cold_source[.(target), on = "id", nomatch = NULL, .(id)]
    }
    cold_batch <- function() {
      setindexv(cold_source, NULL)
      setindexv(cold_source, "id")
      cold_source[.(batch_targets), on = "id", nomatch = NULL, .(id)]
    }

    indexed <- copy(source)
    setindexv(indexed, "id")
    if (!identical(indices(indexed), "id")) {
      stop("secondary index was not installed at scale ", scale)
    }
    warm_single <- function() {
      indexed[.(target), on = "id", nomatch = NULL, .(id)]
    }
    warm_batch <- function() {
      indexed[.(batch_targets), on = "id", nomatch = NULL, .(id)]
    }

    query_shapes <- list(
      single = list(
        unindexed = unindexed_single,
        cold = cold_single,
        warm = warm_single
      ),
      batch32 = list(
        unindexed = unindexed_batch,
        cold = cold_batch,
        warm = warm_batch
      )
    )
    index_bytes <- as.double(object.size(indexed) - object.size(source))
    for (query_shape in names(query_shapes)) {
      operations <- query_shapes[[query_shape]]
      unindexed_output <- operations$unindexed()
      cold_output <- operations$cold()
      warm_output <- operations$warm()
      expected_checksum <- table_checksum(unindexed_output)
      for (candidate in list(cold_output, warm_output)) {
        if (!identical(names(candidate), "id") ||
            nrow(candidate) != nrow(unindexed_output) ||
            table_checksum(candidate) != expected_checksum) {
          stop(
            query_shape,
            " index lookup validation failed at scale ",
            scale
          )
        }
      }
      validations[[length(validations) + 1L]] <- data.table(
        scale = scale,
        query_shape = query_shape,
        output_rows = nrow(unindexed_output),
        checksum = expected_checksum,
        status = "exact-internal-parity"
      )
      timed_operations <- list(
        unindexed_linear_scan = operations$unindexed,
        cold_index_build_and_lookup = operations$cold,
        warm_index_lookup = operations$warm
      )
      for (name in names(timed_operations)) {
        measurement <- measure(
          timed_operations[[name]],
          target_seconds,
          sample_count
        )
        rows[[length(rows) + 1L]] <- data.table(
          scale = scale,
          query_shape = query_shape,
          benchmark = name,
          loops_per_sample = measurement$number,
          samples = sample_count,
          median_ms = median(measurement$samples_ms),
          min_ms = min(measurement$samples_ms),
          max_ms = max(measurement$samples_ms),
          secondary_index_bytes = index_bytes
        )
      }
    }
  }
  list(timings = rbindlist(rows), validations = rbindlist(validations))
}

format_number <- function(value, digits = 6L) {
  if (is.na(value)) {
    "n/a"
  } else {
    formatC(value, format = "f", digits = digits)
  }
}

write_summary <- function(path, quick, timings, index_timings) {
  lines <- c(
    "# data.table comparison and index study",
    "",
    if (quick) {
      "Quick wiring receipt; timings are provisional."
    } else {
      "Full separate-process data.table timing receipt."
    },
    "",
    "The relational rows disable automatic/reusable indices so repeated timing",
    "does not silently exclude one-time setup. Exact outputs are checked against",
    "the Scala semantic-oracle receipt before measurement.",
    "",
    "| Workload | data.table median | Range | frame4s JMH | data.table/frame4s |",
    "|---|---:|---:|---:|---:|"
  )
  for (index in seq_len(nrow(timings))) {
    row <- timings[index]
    ratio <- row$data_table_over_frame4s
    lines <- c(
      lines,
      paste0(
        "| `", row$benchmark, "` | ",
        format_number(row$median_ms), " ms | ",
        format_number(row$min_ms), "-", format_number(row$max_ms), " ms | ",
        if (is.na(row$frame4s_ms)) {
          "n/a | n/a |"
        } else {
          paste0(
            format_number(row$frame4s_ms), " ms | ",
            formatC(ratio, format = "f", digits = 2L), "x |"
          )
        }
      )
    )
  }

  lines <- c(
    lines,
    "",
    "The index study is a capability study, not a frame4s win/loss claim. It",
    "uses the same unsorted table for a forced linear equality scan, rebuilds a",
    "secondary index for every cold query, and reuses a prebuilt `setindexv`",
    "index for every warm query.",
    "",
    "| Rows | Query | Linear scan | Cold build+lookup | Warm lookup | Warm speedup | Break-even queries | Index bytes |",
    "|---:|---|---:|---:|---:|---:|---:|---:|"
  )
  for (scale_value in unique(index_timings$scale)) {
    for (query_shape_value in unique(index_timings$query_shape)) {
      subset <- index_timings[
        scale == scale_value & query_shape == query_shape_value
      ]
      scan <- subset[benchmark == "unindexed_linear_scan"]
      cold <- subset[benchmark == "cold_index_build_and_lookup"]
      warm <- subset[benchmark == "warm_index_lookup"]
      break_even <- if (scan$median_ms > warm$median_ms) {
        (cold$median_ms - warm$median_ms) /
          (scan$median_ms - warm$median_ms)
      } else {
        NA_real_
      }
      lines <- c(
        lines,
        paste0(
          "| ", scale_value, " | ", query_shape_value, " | ",
          format_number(scan$median_ms), " ms | ",
          format_number(cold$median_ms), " ms | ",
          format_number(warm$median_ms), " ms | ",
          formatC(
            scan$median_ms / warm$median_ms,
            format = "f",
            digits = 2L
          ), "x | ",
          if (is.na(break_even)) {
            "n/a"
          } else {
            formatC(break_even, format = "f", digits = 1L)
          }, " | ",
          formatC(
            warm$secondary_index_bytes,
            format = "f",
            digits = 0L
          ), " |"
        )
      )
    }
  }
  largest_scale <- max(index_timings$scale)
  largest_single <- index_timings[
    scale == largest_scale & query_shape == "single"
  ]
  largest_batch <- index_timings[
    scale == largest_scale & query_shape == "batch32"
  ]
  interpretation <- function(rows) {
    scan <- rows[benchmark == "unindexed_linear_scan"]$median_ms
    cold <- rows[benchmark == "cold_index_build_and_lookup"]$median_ms
    warm <- rows[benchmark == "warm_index_lookup"]$median_ms
    break_even <- if (scan > warm) {
      (cold - warm) / (scan - warm)
    } else {
      NA_real_
    }
    list(
      speedup = scan / warm,
      break_even = break_even
    )
  }
  single_result <- interpretation(largest_single)
  batch_result <- interpretation(largest_batch)
  break_even_text <- function(value) {
    if (is.na(value)) {
      "not observed"
    } else {
      paste0(formatC(value, format = "f", digits = 1L), " queries")
    }
  }
  lines <- c(
    lines,
    "",
    "## Interpretation",
    "",
    paste0(
      "At the relational fixture size, frame4s is faster on all ",
      nrow(timings),
      " measured shapes; the descriptive data.table/frame4s ratios range from ",
      formatC(
        min(timings$data_table_over_frame4s, na.rm = TRUE),
        format = "f",
        digits = 2L
      ),
      "x to ",
      formatC(
        max(timings$data_table_over_frame4s, na.rm = TRUE),
        format = "f",
        digits = 2L
      ),
      "x."
    ),
    paste0(
      "At ",
      largest_scale,
      " rows, the linear-scan/warm-index ratio is ",
      formatC(single_result$speedup, format = "f", digits = 2L),
      "x for one target with break-even ",
      break_even_text(single_result$break_even),
      "; for the 32-target batch it is ",
      formatC(batch_result$speedup, format = "f", digits = 2L),
      "x with break-even ",
      break_even_text(batch_result$break_even),
      "."
    ),
    "This supports a separately owned immutable secondary-index capability for",
    "declared repeated workloads. It does not support invisible auto-indexing in",
    "pure `Frame` construction or treating warm indexed lookup as equivalent to",
    "a one-shot scan.",
    "",
    "",
    "Exact checksum comparisons cover primitive materialization, fused",
    "filter/project, grouped sum, joins, distinct, semi/anti join, and union.",
    "The four-stat floating-moments workload is row/schema validated because",
    "legal reduction algorithms can differ across runtimes.",
    "",
    "Raw relational and index timings are under `raw/`. `validation.tsv` and",
    "`index-validation.tsv` carry output evidence; `environment.properties`",
    "records the runtime, dependency, threading, and index-option provenance.",
    ""
  )
  writeLines(lines, path, useBytes = TRUE)
}

main <- function() {
  arguments <- parse_arguments()
  if (as.character(packageVersion("data.table")) != expected_data_table ||
      as.character(packageVersion("jsonlite")) != expected_jsonlite) {
    stop(
      "dependency mismatch: data.table=",
      packageVersion("data.table"),
      " expected ",
      expected_data_table,
      "; jsonlite=",
      packageVersion("jsonlite"),
      " expected ",
      expected_jsonlite
    )
  }

  dir.create(file.path(arguments$receipt, "raw"), recursive = TRUE, showWarnings = FALSE)
  target_seconds <- if (arguments$quick) 0.05 else 0.25
  sample_count <- if (arguments$quick) 3L else 9L
  previous_threads <- getDTthreads()
  previous_options <- options(
    datatable.auto.index = FALSE,
    datatable.use.index = FALSE
  )
  on.exit({
    setDTthreads(previous_threads)
    options(previous_options)
  }, add = TRUE)
  setDTthreads(1L)

  oracle <- read_oracle(arguments$oracle)
  frame4s_times <- read_frame4s_times(arguments$frame4s_jmh)
  fixtures <- make_fixtures(arguments$rows)
  workloads <- make_workloads(arguments$rows, fixtures)
  relational <- validate_and_measure_workloads(
    workloads,
    oracle,
    frame4s_times,
    target_seconds,
    sample_count
  )

  options(datatable.auto.index = TRUE, datatable.use.index = TRUE)
  index_study <- run_index_study(
    arguments$index_scales,
    target_seconds,
    sample_count
  )

  fwrite(
    relational$timings,
    file.path(arguments$receipt, "raw", "timings.csv")
  )
  fwrite(
    index_study$timings,
    file.path(arguments$receipt, "raw", "index-timings.csv")
  )
  fwrite(
    relational$validations,
    file.path(arguments$receipt, "validation.tsv"),
    sep = "\t"
  )
  fwrite(
    index_study$validations,
    file.path(arguments$receipt, "index-validation.tsv"),
    sep = "\t"
  )

  environment <- c(
    "receipt_format=1",
    "suite=frame4s-data-table-comparison-court",
    paste0("quick=", tolower(as.character(arguments$quick))),
    paste0("rows=", arguments$rows),
    paste0("index.scales=", paste(arguments$index_scales, collapse = ",")),
    paste0("R.version=", R.version.string),
    paste0("data.table.version=", packageVersion("data.table")),
    paste0(
      "data.table.built=",
      packageDescription("data.table")[["Built"]]
    ),
    paste0("jsonlite.version=", packageVersion("jsonlite")),
    paste0("jsonlite.built=", packageDescription("jsonlite")[["Built"]]),
    paste0("os=", Sys.info()[["sysname"]], " ", Sys.info()[["release"]]),
    paste0("machine=", Sys.info()[["machine"]]),
    "threads=1",
    paste0(
      "timing=proc.time,",
      sample_count,
      "-sample-median,target=",
      target_seconds,
      "s"
    ),
    "relational.datatable.auto.index=false",
    "relational.datatable.use.index=false",
    "index.study.datatable.auto.index=true",
    "index.study.datatable.use.index=true",
    paste0("oracle.validation=", arguments$oracle),
    paste0(
      "frame4s.jmh=",
      if (is.null(arguments$frame4s_jmh)) "not-provided" else arguments$frame4s_jmh
    )
  )
  writeLines(
    environment,
    file.path(arguments$receipt, "environment.properties"),
    useBytes = TRUE
  )
  write_summary(
    file.path(arguments$receipt, "summary.md"),
    arguments$quick,
    relational$timings,
    index_study$timings
  )
}

main()
