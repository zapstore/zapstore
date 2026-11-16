package dev.zapstore.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Local diagnostic log: `files/diagnostics/app.log`, rotated to `app.log.1` past [MAX_BYTES].
 * Never write keys, NWC strings, or event content here; Diagnostics exports this file.
 * A crash is also written to `files/diagnostics/crashes/` so the next start can report it.
 */
object AppLog {
    private const val MAX_BYTES = 256 * 1024L
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "AppLog").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    @Volatile private var dir: File? = null

    /** Told about each [e] call, for bug reports. Must not log at error level itself. */
    @Volatile var onError: ((tag: String, message: String, error: Throwable?) -> Unit)? = null

    fun init(context: Context) {
        val logs = File(context.filesDir, "diagnostics").apply { mkdirs() }
        dir = logs
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (isSystemDeath(error)) noteSystemDeath(logs, error) else writeCrash(logs, thread, error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun i(tag: String, message: String) = write(Log.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = write(Log.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) {
        write(Log.ERROR, tag, message, error)
        onError?.let { listener -> runCatching { listener(tag, message, error) } }
    }

    /** The log files, oldest first. */
    fun files(): List<File> {
        val logs = dir ?: return emptyList()
        return listOf(File(logs, "app.log.1"), File(logs, "app.log")).filter { it.exists() }
    }

    /** Crash reports waiting to be sent, oldest first. */
    fun pendingCrashes(): List<File> =
        dir?.let { File(it, "crashes").listFiles()?.sortedBy { file -> file.name } }.orEmpty()

    fun clear() {
        synchronized(seenTraces) { seenTraces.clear() }
        writer.execute { dir?.listFiles()?.forEach { it.deleteRecursively() } }
    }

    private fun write(priority: Int, tag: String, message: String, error: Throwable?) {
        runCatching { Log.println(priority, tag, if (error == null) message else "$message\n${Log.getStackTraceString(error)}") }
        val logs = dir ?: return
        val line = line(priority, tag, message, error, System.currentTimeMillis())
        writer.execute {
            runCatching {
                val file = File(logs, "app.log")
                if (file.length() > MAX_BYTES) file.renameTo(File(logs, "app.log.1"))
                file.appendText(line)
            }
        }
    }

    private fun writeCrash(logs: File, thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val crashes = File(logs, "crashes").apply { mkdirs() }
        val text = line(Log.ASSERT, "crash", "Uncaught on ${thread.name}", error, now)
        File(crashes, "$now.txt").writeText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n$text")
        File(logs, "app.log").appendText(text)
    }

    /**
     * The system process died, so every binder call this process makes throws and the process is
     * going down with it. Nothing here failed, so it is noted in one line and never queued as a
     * report. Written straight to the file: the log's writer thread may not run again.
     */
    private fun noteSystemDeath(logs: File, error: Throwable) {
        val text = line(Log.WARN, "crash", "system died while the process was tearing down: $error", null, System.currentTimeMillis())
        File(logs, "app.log").appendText(text)
    }

    /** True when [error], or anything it carries, is the system process dying. */
    internal fun isSystemDeath(error: Throwable): Boolean = chain(error).any { isSystemDeathName(it.javaClass.name) }

    /** DeadSystemRuntimeException is hidden from the SDK, so both names are matched as text. */
    internal fun isSystemDeathName(name: String): Boolean =
        name == "android.os.DeadSystemException" || name == "android.os.DeadSystemRuntimeException"

    /** [error] with its causes and suppressed errors, which a framework teardown nests several deep. */
    internal fun chain(error: Throwable): List<Throwable> {
        val seen = LinkedHashSet<Throwable>()
        val queue = ArrayDeque<Throwable>()
        queue.addLast(error)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            // A cause or a suppressed error can point back into the chain.
            if (!seen.add(next)) continue
            next.cause?.let(queue::addLast)
            next.suppressed.forEach(queue::addLast)
        }
        return seen.toList()
    }

    internal fun line(priority: Int, tag: String, message: String, error: Throwable?, at: Long): String {
        val level = when (priority) {
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "F"
            else -> "D"
        }
        val time = synchronized(stamp) { stamp.format(Date(at)) }
        val trace = error?.let { StringWriter().also { out -> it.printStackTrace(PrintWriter(out)) }.toString() }.orEmpty()
        if (priority != Log.ASSERT && trace.isNotEmpty() && !firstSighting(trace)) {
            return "$time $level/$tag: $message: ${trace.substringBefore('\n').trimEnd()} (trace above)\n"
        }
        return "$time $level/$tag: $message\n$trace"
    }

    /** Traces already written by this process, so a failure repeated across many apps or runs of a loop costs a line. */
    private val seenTraces = LinkedHashSet<Int>()

    private fun firstSighting(trace: String): Boolean = synchronized(seenTraces) {
        if (!seenTraces.add(trace.hashCode())) return false
        if (seenTraces.size > MAX_SEEN_TRACES) seenTraces.remove(seenTraces.first())
        true
    }

    private const val MAX_SEEN_TRACES = 256
}
