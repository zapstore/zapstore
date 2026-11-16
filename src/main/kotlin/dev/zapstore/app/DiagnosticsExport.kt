package dev.zapstore.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Writes the whole local log to one text file and hands out a share URI for it. */
object DiagnosticsExport {
    private const val DIR = "diagnostics"
    private const val NAME = "zapstore-diagnostics.txt"

    fun authority(context: Context) = "${context.packageName}.diagnostics"

    /** The URI of the full log, or null when there is nothing to share. */
    fun export(context: Context): Uri? {
        val files = AppLog.files()
        if (files.isEmpty()) return null
        val target = File(File(context.cacheDir, DIR).apply { mkdirs() }, NAME)
        target.outputStream().use { out -> files.forEach { it.inputStream().use { input -> input.copyTo(out) } } }
        return FileProvider.getUriForFile(context, authority(context), target)
    }
}
