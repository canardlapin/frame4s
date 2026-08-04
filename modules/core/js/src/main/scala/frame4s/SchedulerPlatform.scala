package frame4s

private[frame4s] object SchedulerPlatform:
  /** Scala.js has no shared-memory threads, so execution is always inline.
    *
    * Web Workers exist but communicate by copying messages, which cannot be reconciled with
    * borrowed column buffers. Keeping this sequential means the JS and JVM backends stay
    * observationally identical rather than merely similar.
    */
  val default: Scheduler = SequentialScheduler
