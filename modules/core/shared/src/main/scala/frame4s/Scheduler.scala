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

/** Policy for splitting work, and a measured lesson about which kernels are worth splitting.
  *
  * Two partitioned grouped aggregations were built on this and both rejected. The first let
  * every worker scan every row to find the keys it owned, which divides the accumulate work
  * by P but multiplies the scan by P. The second fixed that with a counting-sort partition
  * pass, so each row is visited exactly once per phase. It made almost no difference, which
  * is the useful part of the result. High-cardinality grouping at 1,000,000 rows, against
  * 45.1 ms sequential:
  *
  *   partitions   1      2      4      16
  *   duplicated scan     --     50.2   53.9   124.3 ms
  *   single scan  --     50.2   --     122.4 ms
  *
  * Time rises monotonically with worker count. A million-group build is bound by random
  * access latency into a table far larger than cache, not by compute, and adding workers adds
  * concurrent random-access streams to a memory subsystem that is already the bottleneck.
  * More threads make it worse rather than merely failing to help. No partition count, and no
  * cheaper partition pass, rescues this shape; the data structure has to change first.
  *
  * The implication is that parallelism should go first to kernels that stream rather than
  * chase pointers -- filter, projection, arithmetic -- where access is sequential and
  * bandwidth scales with cores. Grouping and joins need a more compact, cache-resident table
  * before threading them is worth attempting again.
  *
  * The determinism design was verified and should be reused when that happens: partitioning
  * by key rather than by row keeps every row of a group on one worker in input order, so
  * floating sums stay bit-identical instead of merely close, and recovering output order
  * needs only each group's first-seen input ordinal and a merge across partitions. Checked
  * end to end at 200,000 rows against a pre-parallelism receipt, floating aggregates included.
  */
private[frame4s] object Parallelism:
  /** Rows below which splitting costs more than it saves.
    *
    * The ratified 1,000-row court must not regress, and at that size the whole fixture is a
    * single 1024-row batch, so this threshold keeps the small tier on exactly the sequential
    * path it was measured on.
    */
  val MinimumRows = 65536

  /** Smallest row chunk worth handing to a worker.
    *
    * Below this the scheduling handshake costs more than the work, and the chunk's output
    * arrays are too small to amortize their own allocation.
    */
  val MinimumChunkRows = 16384

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
