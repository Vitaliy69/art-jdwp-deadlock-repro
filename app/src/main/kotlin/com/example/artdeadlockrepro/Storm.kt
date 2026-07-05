package com.example.artdeadlockrepro

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * The reproduction workload for the ART/JDWP + GC lock-ordering deadlock
 * (Google Issue Tracker #530992434).
 *
 * Everything runs on background threads (started after the first frame), so the UI is
 * responsive on a plain `Run`. Under a debugger the same workload triggers the
 * whole-VM deadlock.
 *
 * The intensity matters — the deadlock is a race, so the signals must be dense and
 * concurrent, not throttled:
 *   (1) CLASS_PREPARE storm — load ALL generated classes as fast as possible, in
 *       parallel across several threads (mirrors a real app's parallel startup);
 *   (2) THREAD_END storm    — churn short-lived threads;
 *   (3) allocation pressure — transient garbage to keep the GC running (nothing retained).
 * (2) and (3) are kept alive for ~25s so "Run, then Attach to the live process" also
 * lands inside the window.
 */
object Storm {
    private const val TAG = "ArtDeadlockRepro"

    private const val LOADER_THREADS = 8
    private const val CHURN_THREADS = 2
    private const val STORM_DURATION_NANOS = 25_000_000_000L // ~25s

    @Volatile private var started = false

    @Synchronized
    fun startOnce(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        thread(name = "repro-storm", isDaemon = true) { run(appContext) }
    }

    private fun run(context: Context) {
        val count = BuildConfig.REPRO_CLASS_COUNT
        val classLoader = context.classLoader
        val kept = ConcurrentLinkedQueue<Any>() // keep loaded classes referenced
        Log.i(TAG, "storm start: $count classes, $LOADER_THREADS loaders")

        // (1) CLASS_PREPARE storm: blast all classes as fast as possible, in parallel.
        val loaders = (0 until LOADER_THREADS).map { shard ->
            thread(name = "repro-loader-$shard", isDaemon = true) {
                var i = shard
                while (i < count) {
                    try {
                        kept += Class.forName("gen.Gen$i", true, classLoader)
                    } catch (_: Throwable) {
                        // build may have generated a different count; ignore
                    }
                    i += LOADER_THREADS
                }
            }
        }

        // (2)+(3) THREAD_END + allocation churn, sustained for the whole window.
        val deadline = System.nanoTime() + STORM_DURATION_NANOS
        val churners = (0 until CHURN_THREADS).map { c ->
            thread(name = "repro-churn-$c", isDaemon = true) {
                var sink = 0L
                while (System.nanoTime() < deadline) {
                    // short-lived threads that immediately exit -> THREAD_END events
                    repeat(20) {
                        Thread({ Math.sqrt(it.toDouble()) }, "repro-worker").apply {
                            isDaemon = true
                            start()
                        }
                    }
                    // transient garbage only (not retained) -> frequent GC, no OOM on Run
                    repeat(150) {
                        val b = ByteArray(64 * 1024)
                        b[0] = 1
                        sink += b.size
                    }
                    Thread.sleep(5)
                }
                if (sink == -1L) Log.v(TAG, "unreachable") // keep sink live
            }
        }

        loaders.forEach { it.join() }
        Log.i(TAG, "storm: all classes loaded")
        churners.forEach { it.join() }
        Log.i(TAG, "storm done")
    }
}
