package com.example.telescopecontrol.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One ordered, bounded, non-blocking producer queue. Disk work stays on its dedicated worker. */
class EngineeringSessionLogger(
    private val directory: File,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val onError: (String) -> Unit = {},
    capacity: Int = 4096,
    private val maxSessionBytes: Long = 1024 * 1024
) {
    private sealed class Work {
        class Start(val file: File, val header: String) : Work()
        class Line(val file: File, val text: String) : Work()
        class Finish(val file: File, val text: String) : Work()
        class Snapshot(val source: File?, val callback: (Result<String>) -> Unit) : Work()
    }

    private val queue = ArrayBlockingQueue<Work>(capacity.coerceAtLeast(16) + 4)
    private val recordCapacity = capacity.coerceAtLeast(16)
    private val lock = Any()
    private val lost = AtomicLong()
    private var active: File? = null
    private var latest: File? = null
    private var sequence = 0L
    private var closed = false
    private val shutdownRequested = AtomicBoolean(false)
    private val worker = thread(name = "engineering-log", isDaemon = true) {
        var writer: BufferedWriter? = null
        var output: File? = null
        var bytesWritten = 0L
        var truncated = false
        fun append(text: String) {
            val current = writer ?: error("Engineering log writer unavailable")
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (!truncated && bytesWritten + bytes <= maxSessionBytes.coerceAtLeast(1024L)) {
                current.write(text)
                bytesWritten += bytes
            } else if (!truncated) {
                current.write("LOG_TRUNCATED session size limit reached; later records omitted\n")
                truncated = true
                onError("Engineering log session size limit reached; later records omitted")
            }
        }
        while (true) {
            val work = queue.poll(250, TimeUnit.MILLISECONDS)
            if (work == null) {
                try {
                    if (shutdownRequested.get()) { writer?.close(); return@thread }
                    writer?.flush()
                } catch (e: Exception) {
                    onError("Engineering log: ${e.message ?: e.javaClass.simpleName}")
                    if (shutdownRequested.get()) return@thread
                }
                continue
            }
            try {
                when (work) {
                    is Work.Start -> {
                        writer?.close()
                        if (!directory.exists() && !directory.mkdirs()) error("Cannot create log directory")
                        output = work.file
                        writer = work.file.bufferedWriter(Charsets.UTF_8)
                        bytesWritten = 0L
                        truncated = false
                        append(work.header)
                        directory.listFiles()?.filter { it.isFile && it != work.file && it.name.startsWith("session-") && it.name.endsWith(".log") }
                            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
                            ?.drop(9)?.forEach { if (!it.delete()) onError("Unable to remove old log ${it.name}") }
                    }
                    is Work.Line -> if (output == work.file) {
                        append(work.text)
                    } else error("Engineering log session unavailable")
                    is Work.Finish -> if (output == work.file) {
                        append(work.text); writer?.close(); writer = null; output = null
                    } else error("Engineering log session unavailable")
                    is Work.Snapshot -> {
                        writer?.flush()
                        val source = work.source ?: directory.listFiles()
                            ?.filter { it.isFile && it.name.startsWith("session-") && it.name.endsWith(".log") }
                            ?.maxByOrNull { it.lastModified() }
                            ?: error("No engineering session is available")
                        work.callback(Result.success(source.readText(Charsets.UTF_8)))
                    }
                }
            } catch (e: Exception) {
                if (work is Work.Snapshot) work.callback(Result.failure(e))
                else onError("Engineering log: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun line(category: String, generation: Long, message: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(wallMillis()))
        val bounded = if (message.length > 4096) message.take(4096) + " [message truncated]" else message
        val safeMessage = bounded.replace("\r", "\\r").replace("\n", "\\n")
        return "$timestamp elapsed_ms=${elapsedMillis()} seq=${++sequence} gen=$generation category=$category $safeMessage\n"
    }

    private fun enqueue(work: Work): Boolean {
        // Reserve slots for session boundaries and snapshots even under record overload.
        if ((work !is Work.Line || queue.size < recordCapacity) && queue.offer(work)) return true
        if (lost.incrementAndGet() == 1L) onError("Engineering log queue full; records were lost")
        return false
    }

    fun startSession(description: String, generation: Long) = synchronized(lock) {
        if (closed) return@synchronized
        active?.let { enqueue(Work.Finish(it, line("USB", generation, "SESSION_END superseded"))) }
        lost.set(0)
        val file = File(directory, "session-${wallMillis()}-${UUID.randomUUID()}.log")
        sequence = 0
        if (enqueue(Work.Start(file, line("USB", generation, "SESSION_START $description")))) {
            active = file
            latest = file
        } else active = null
    }

    fun record(category: String, generation: Long, message: String) = synchronized(lock) {
        val file = active ?: return@synchronized
        if (!closed) enqueue(Work.Line(file, line(category, generation, message)))
    }

    fun endSession(generation: Long, reason: String) = synchronized(lock) {
        val file = active ?: return@synchronized
        enqueue(Work.Finish(file, line("USB", generation, "SESSION_END $reason dropped=${lost.get()}")))
        active = null
    }

    /** The callback runs on the writer thread after all preceding records have been flushed. */
    fun snapshot(callback: (Result<String>) -> Unit) = synchronized(lock) {
        if (closed || !enqueue(Work.Snapshot(active ?: latest, callback))) {
            callback(Result.failure(IllegalStateException("Engineering log unavailable or queue full")))
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            active?.let { enqueue(Work.Finish(it, line("USB", 0, "SESSION_END logger closed dropped=${lost.get()}"))) }
            active = null
            closed = true
            // The worker drains all accepted work before exiting, without blocking producers.
            shutdownRequested.set(true)
        }
    }
}
