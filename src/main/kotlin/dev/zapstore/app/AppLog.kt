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
            runCatching { writeCrash(logs, thread, error) }
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

    private fun line(priority: Int, tag: String, message: String, error: Throwable?, at: Long): String {
        val level = when (priority) {
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "F"
            else -> "D"
        }
        val time = synchronized(stamp) { stamp.format(Date(at)) }
        val trace = error?.let { StringWriter().also { out -> it.printStackTrace(PrintWriter(out)) }.toString() }.orEmpty()
        return "$time $level/$tag: $message\n$trace"
    }
}
