package dev.zapstore.app.search

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import dev.zapstore.iolite.LeafQueryEncoder
import java.io.File
import java.io.IOException

/**
 * Installs the bundled leaf-ir-v1 files and releases the encoder under memory pressure.
 * Encoding itself is [LeafQueryEncoder].
 */
class QueryEncoder(context: Context) : ComponentCallbacks2 {
    private val encoder = LeafQueryEncoder(install(context.applicationContext))

    fun encode(query: String): ByteArray? = encoder.encode(query)

    fun release() = encoder.release()

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) release()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private companion object {
        const val MODEL_REV = "4262131b32c3182bd06e67e92ae69d7bd66e0c5c"
        val MODEL_FILES = listOf("vocab.txt", "dense.safetensors", "model_quantized.onnx", "model_quantized.onnx_data")

        fun install(context: Context): File {
            val dest = File(context.filesDir, "leaf-ir-v1")
            val marker = File(dest, "revision")
            if (marker.isFile && marker.readText() == MODEL_REV && File(dest, "model_quantized.onnx").isFile) return dest
            dest.mkdirs()
            try {
                for (name in MODEL_FILES) {
                    context.assets.open("leaf-ir-v1/$name").use { input ->
                        File(dest, "$name.tmp").outputStream().use { output -> input.copyTo(output) }
                    }
                    val tmp = File(dest, "$name.tmp")
                    val file = File(dest, name)
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                }
            } catch (error: IOException) {
                throw IOException("query model is not in the app", error)
            }
            marker.writeText(MODEL_REV)
            return dest
        }
    }
}
