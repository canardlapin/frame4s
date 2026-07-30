package frame4s

/** Runs independent CPU-bound tasks, possibly in parallel.
  *
  * `frame4s-core` has no external runtime dependency and is cross-built for Scala.js, so the
  * only thing shared across platforms is this interface. The JVM supplies a work-stealing
  * implementation; Scala.js supplies a sequential one, because a browser or Node runtime has
  * no shared-memory threads to offer.
  *
  * Tasks handed to `runAll` must be independent and must not throw: kernels collect their own
  * errors into per-task slots rather than propagating exceptions across a pool boundary.
  */
private[frame4s] trait Scheduler:
  /** Number of tasks that can make progress at once. `1` means everything runs inline. */
  def parallelism: Int

  /** Run every task and return once all have completed. */
  def runAll(tasks: Array[Runnable]): Unit

private[frame4s] object Scheduler:
  /** The platform's default scheduler: work-stealing on the JVM, inline on Scala.js. */
  def default: Scheduler = SchedulerPlatform.default

private[frame4s] object SequentialScheduler extends Scheduler:
  val parallelism = 1

  def runAll(tasks: Array[Runnable]): Unit =
    var index = 0
    while index < tasks.length do
      tasks(index).run()
      index += 1

/** Policy for splitting work, and one measured lesson about how not to.
  *
  * A partitioned grouped aggregation was built on this and rejected. Each of P workers owned
  * a share of the key space and scanned every row to find its own, which divides the
  * accumulate work by P but multiplies the scan by P. At 1,000,000 rows and a million groups
  * it measured 124.3 ms with 16 partitions and 53.9 ms with 4, against 45.1 ms sequential --
  * a loss at every count, because scanning is not the cheap part once the hash table no
  * longer fits in cache.
  *
  * The determinism half of that design was sound and should be reused: partitioning by key
  * rather than by row means every row of a group is folded by one worker in input order, so
  * floating sums stay bit-identical instead of merely close, which a row split could never
  * offer. What it needs is a partition pass that visits each row once and materializes
  * per-partition row lists, so the scan is divided rather than duplicated.
  */
private[frame4s] object Parallelism:
  /** Rows below which splitting costs more than it saves.
    *
    * The ratified 1,000-row court must not regress, and at that size the whole fixture is a
    * single 1024-row batch, so this threshold keeps the small tier on exactly the sequential
    * path it was measured on.
    */
  val MinimumRows = 65536

  /** How many partitions to use, as a power of two so hashing can mask instead of divide.
    *
    * Read the note on `Parallelism` before choosing a partition count for a new kernel: a
    * partition scheme that makes every worker examine every row loses at any count.
    */
  def partitions(rows: Int, scheduler: Scheduler): Int =
    if rows < MinimumRows || scheduler.parallelism <= 1 then 1
    else
      var count = 1
      while count < scheduler.parallelism do count *= 2
      count
