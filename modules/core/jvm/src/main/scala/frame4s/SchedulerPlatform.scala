package frame4s

import java.util.concurrent.{ForkJoinPool, ForkJoinTask}

private[frame4s] object SchedulerPlatform:
  /** The pool frame4s owns.
    *
    * A dedicated `ForkJoinPool` rather than `commonPool`, so a neighbouring library's
    * parallel stream cannot starve query execution and vice versa. Work stealing matters
    * here beyond load balancing: the court's skewed join and group fixtures are deliberately
    * lopsided, and a fixed partition-per-thread split would leave most workers idle on them.
    *
    * One thread is left to the caller, which is the thread that submits the work and then
    * participates in it.
    */
  lazy val default: Scheduler =
    val available = Runtime.getRuntime.availableProcessors()
    if available <= 1 then SequentialScheduler
    else new ForkJoinScheduler(available - 1)

final private class ForkJoinScheduler(val parallelism: Int) extends Scheduler:
  private val pool = new ForkJoinPool(parallelism)

  def runAll(tasks: Array[Runnable]): Unit =
    if tasks.length == 1 then tasks(0).run()
    else if tasks.length > 1 then
      val submitted = new Array[ForkJoinTask[?]](tasks.length - 1)
      var index = 1
      while index < tasks.length do
        submitted(index - 1) = pool.submit(tasks(index))
        index += 1
      // The submitting thread runs the first task instead of blocking, so a scheduler with
      // parallelism N keeps N+1 threads busy and never idles the caller.
      tasks(0).run()
      var pending = 0
      while pending < submitted.length do
        submitted(pending).join()
        pending += 1
