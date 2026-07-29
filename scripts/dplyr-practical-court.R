#!/usr/bin/env Rscript

suppressPackageStartupMessages({
  library(dplyr)
  library(jsonlite)
  library(tibble)
})

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
  frame4s_jmh <- argument_value(arguments, "--frame4s-jmh")
  if (is.null(receipt) || is.null(oracle) || is.null(frame4s_jmh)) {
    stop("--receipt, --oracle-validation, and --frame4s-jmh are required")
  }
  rows <- as.integer(argument_value(arguments, "--rows", "10000"))
  if (is.na(rows) || rows <= 0L) {
    stop("--rows must be positive")
  }
  list(
    receipt = receipt,
    oracle = oracle,
    frame4s_jmh = frame4s_jmh,
    rows = rows,
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
    numeric_value <- as.double(value)
    if (numeric_value < 0) {
      return(c(hi = u32_base - 1, lo = u32_base + numeric_value))
    }
    return(c(hi = 0, lo = numeric_value))
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
  for (row in seq_len(nrow(table))) {
    for (column in names(table)) {
      result <- u64_multiply_add(result, scalar_hash(table[[column]][row]))
    }
  }
  u64_to_decimal(result)
}

structure_scalar_hash <- function(value) {
  if (is.double(value) && !is.na(value)) {
    return(c(hi = 0, lo = 0))
  }
  scalar_hash(value)
}

table_structure_checksum <- function(table) {
  result <- c(hi = 0, lo = nrow(table))
  if (nrow(table) == 0L) {
    return(u64_to_decimal(result))
  }
  for (row in seq_len(nrow(table))) {
    for (column in names(table)) {
      result <- u64_multiply_add(
        result,
        structure_scalar_hash(table[[column]][row])
      )
    }
  }
  u64_to_decimal(result)
}

float_signature <- function(table) {
  columns <- which(vapply(table, is.double, logical(1)))
  if (length(columns) == 0L) {
    return("")
  }
  values <- vapply(columns, function(column) {
    valid <- table[[column]][!is.na(table[[column]])]
    if (length(valid) == 0L) {
      minimum <- Inf
      maximum <- -Inf
    } else {
      minimum <- min(valid)
      maximum <- max(valid)
    }
    paste(
      column - 1L,
      length(valid),
      format(sum(valid), digits = 17L, scientific = TRUE, trim = TRUE),
      format(sum(valid * valid), digits = 17L, scientific = TRUE, trim = TRUE),
      format(minimum, digits = 17L, scientific = TRUE, trim = TRUE),
      format(maximum, digits = 17L, scientific = TRUE, trim = TRUE),
      format(
        sum((seq_along(table[[column]])) * replace(table[[column]], is.na(table[[column]]), 0)),
        digits = 17L,
        scientific = TRUE,
        trim = TRUE
      ),
      format(
        sum((seq_along(table[[column]])) *
          replace(table[[column]] * table[[column]], is.na(table[[column]]), 0)),
        digits = 17L,
        scientific = TRUE,
        trim = TRUE
      ),
      sep = ":"
    )
  }, character(1))
  paste(values, collapse = ";")
}

parse_float_signature <- function(value) {
  if (is.na(value) || value == "") {
    return(list())
  }
  entries <- strsplit(value, ";", fixed = TRUE)[[1]]
  setNames(lapply(entries, function(entry) {
    fields <- strsplit(entry, ":", fixed = TRUE)[[1]]
    if (length(fields) != 8L) {
      stop("invalid floating signature entry: ", entry)
    }
    list(
      count = as.double(fields[[2]]),
      values = as.double(fields[3:8])
    )
  }), vapply(entries, function(entry) {
    strsplit(entry, ":", fixed = TRUE)[[1]][[1]]
  }, character(1)))
}

float_signature_matches <- function(actual, expected, tolerance = 1e-10) {
  actual <- parse_float_signature(actual)
  expected <- parse_float_signature(expected)
  if (!identical(names(actual), names(expected))) {
    return(FALSE)
  }
  for (name in names(expected)) {
    if (actual[[name]]$count != expected[[name]]$count) {
      return(FALSE)
    }
    differences <- abs(actual[[name]]$values - expected[[name]]$values)
    scales <- pmax(1, abs(expected[[name]]$values))
    if (any(is.na(differences)) || any(differences > tolerance * scales)) {
      return(FALSE)
    }
  }
  TRUE
}

read_oracle <- function(path) {
  rows <- read.delim(
    path,
    sep = "\t",
    quote = "",
    check.names = FALSE,
    stringsAsFactors = FALSE,
    colClasses = "character"
  )
  setNames(
    lapply(seq_len(nrow(rows)), function(index) {
      list(
        rows = as.integer(rows$output_rows[index]),
        checksum = as.character(rows$checksum[index]),
        structure_checksum = as.character(rows$structure_checksum[index]),
        float_signature = as.character(rows$float_signature[index])
      )
    }),
    rows$benchmark
  )
}

read_frame4s_times <- function(path) {
  content <- fromJSON(path, simplifyVector = FALSE)
  times <- list()
  for (result in content) {
    benchmark <- result$benchmark
    if (!grepl(".PracticalPipelineColumnarCourt.", benchmark, fixed = TRUE) ||
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

make_fixture <- function(rows) {
  index <- 0:(rows - 1L)
  tibble(
    name = sprintf("person-%08d", index),
    species = ifelse(
      index %% 17L == 0L,
      NA_character_,
      sprintf("species-%02d", index %% 16L)
    ),
    sex = ifelse(
      index %% 19L == 0L,
      NA_character_,
      ifelse(index %% 2L == 0L, "female", "male")
    ),
    skin = ifelse(index %% 3L == 0L, "light", "dark"),
    eyes = ifelse(index %% 5L == 0L, "brown", "blue"),
    height = ifelse(index %% 23L == 0L, NA_real_, 150 + as.double(index %% 60L)),
    mass = ifelse(index %% 29L == 0L, NA_real_, 50 + as.double(index %% 100L) / 2)
  )
}

make_workloads <- function(fixture) {
  list(
    filterWithColumnsSelect = function() {
      fixture |>
        filter(skin == "light", eyes == "brown") |>
        mutate(
          heightM = height / 100,
          bmi = mass / (heightM * heightM)
        ) |>
        select(name, species, sex, heightM, bmi)
    },
    selectGroupSummarise = function() {
      fixture |>
        select(species, sex, height, mass) |>
        summarise(
          height = mean(height, na.rm = TRUE),
          mass = mean(mass, na.rm = TRUE),
          .by = c(species, sex)
        )
    }
  )
}

write_receipt <- function(arguments, workloads, oracle, frame4s_times) {
  dir.create(file.path(arguments$receipt, "raw"), recursive = TRUE, showWarnings = FALSE)
  target_seconds <- if (arguments$quick) 0.05 else 0.25
  sample_count <- if (arguments$quick) 3L else 9L
  expected_columns <- list(
    filterWithColumnsSelect = c("name", "species", "sex", "heightM", "bmi"),
    selectGroupSummarise = c("species", "sex", "height", "mass")
  )
  validation <- list()
  timings <- list()
  for (name in names(workloads)) {
    operation <- workloads[[name]]
    output <- operation()
    if (!identical(names(output), expected_columns[[name]])) {
      stop(
        name,
        " produced columns ",
        paste(names(output), collapse = ","),
        " instead of ",
        paste(expected_columns[[name]], collapse = ",")
      )
    }
    checksum <- table_checksum(output)
    structure_checksum <- table_structure_checksum(output)
    floating <- float_signature(output)
    oracle_name <- paste0("PracticalPipelineReferenceCourt.", name)
    expected <- oracle[[oracle_name]]
    if (is.null(expected)) {
      stop("oracle validation is missing ", oracle_name)
    }
    if (nrow(output) != expected$rows ||
        structure_checksum != expected$structure_checksum ||
        !float_signature_matches(floating, expected$float_signature)) {
      stop(
        name,
        " disagreed with the Scala oracle: rows/structure/floating signature ",
        nrow(output),
        "/",
        structure_checksum,
        "/",
        floating,
        " versus ",
        expected$rows,
        "/",
        expected$structure_checksum,
        "/",
        expected$float_signature
      )
    }
    validation[[length(validation) + 1L]] <- data.frame(
      benchmark = paste0("DplyrPracticalCourt.", name),
      oracle = oracle_name,
      output_rows = nrow(output),
      checksum = checksum,
      structure_checksum = structure_checksum,
      float_signature = floating,
      status = "exact-structure-and-1e-10-relative-float-signature",
      stringsAsFactors = FALSE
    )
    measured <- measure(operation, target_seconds, sample_count)
    frame4s_ms <- frame4s_times[[name]]
    median_ms <- median(measured$samples_ms)
    timings[[length(timings) + 1L]] <- data.frame(
      benchmark = name,
      loops_per_sample = measured$number,
      samples = sample_count,
      median_ms = median_ms,
      min_ms = min(measured$samples_ms),
      max_ms = max(measured$samples_ms),
      frame4s_ms = frame4s_ms,
      dplyr_over_frame4s = median_ms / frame4s_ms,
      stringsAsFactors = FALSE
    )
  }
  validation <- do.call(rbind, validation)
  timings <- do.call(rbind, timings)
  write.table(
    validation,
    file.path(arguments$receipt, "validation.tsv"),
    sep = "\t",
    quote = FALSE,
    row.names = FALSE
  )
  write.csv(
    timings,
    file.path(arguments$receipt, "raw", "timings.csv"),
    quote = FALSE,
    row.names = FALSE
  )
  timings
}

write_environment <- function(arguments) {
  properties <- c(
    "receipt_format=1",
    "suite=dplyr-practical-pipeline-court",
    paste0("quick=", tolower(as.character(arguments$quick))),
    paste0("rows=", arguments$rows),
    paste0("r.version=", R.version.string),
    paste0("dplyr.version=", as.character(packageVersion("dplyr"))),
    paste0("tibble.version=", as.character(packageVersion("tibble"))),
    paste0("jsonlite.version=", as.character(packageVersion("jsonlite"))),
    "threads=single-process",
    "fixture.construction=outside-timing",
    "query.result=eager-tibble"
  )
  writeLines(properties, file.path(arguments$receipt, "environment.properties"))
}

write_summary <- function(arguments, timings) {
  status <- if (arguments$quick) {
    "Quick wiring receipt; timings are provisional."
  } else {
    "Full separate-process dplyr timing receipt."
  }
  rows <- vapply(seq_len(nrow(timings)), function(index) {
    row <- timings[index, ]
    sprintf(
      "| `%s` | %.6f ms | %.6f-%.6f ms | %.6f ms | %.2fx |",
      row$benchmark,
      row$median_ms,
      row$min_ms,
      row$max_ms,
      row$frame4s_ms,
      row$dplyr_over_frame4s
    )
  }, character(1))
  summary <- c(
    "# dplyr practical pipeline comparison",
    "",
    status,
    "",
    "Both eager pipelines use a prebuilt tibble and match the Scala semantic oracle's",
    "output row count, ordered non-floating/null structure, and floating-column",
    "signatures before timing. Row-weighted moments bind floating values to output",
    "order; all floating statistics use a 1e-10 relative tolerance.",
    "",
    "| Workload | dplyr median | Range | frame4s JMH | dplyr/frame4s |",
    "|---|---:|---:|---:|---:|",
    rows,
    "",
    "The runtimes execute in separate processes. Ratios are descriptive and retain every",
    "visible loss; JVM allocation is reported in the sibling frame4s receipt."
  )
  writeLines(summary, file.path(arguments$receipt, "summary.md"))
}

main <- function() {
  arguments <- parse_arguments()
  dir.create(arguments$receipt, recursive = TRUE, showWarnings = FALSE)
  fixture <- make_fixture(arguments$rows)
  workloads <- make_workloads(fixture)
  oracle <- read_oracle(arguments$oracle)
  frame4s_times <- read_frame4s_times(arguments$frame4s_jmh)
  timings <- write_receipt(arguments, workloads, oracle, frame4s_times)
  write_environment(arguments)
  write_summary(arguments, timings)
}

if (sys.nframe() == 0L) {
  main()
}
