package dev.zapstore.app.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import dev.zapstore.iolite.normalizeSearchQuery
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Encodes a search query with bundled leaf-ir-v1.
 * The call blocks while the model runs; callers should leave the main thread.
 * The session stays open for the search session and closes under memory pressure.
 */
class LeafQueryEncoder private constructor(
    private val context: Context?,
    private val explicitDir: File?,
) : ComponentCallbacks2 {
    constructor(context: Context) : this(context, null)

    internal constructor(modelDir: File) : this(null, modelDir)

    private val lock = Any()
    private var loaded: Loaded? = null

    fun encode(query: String): ByteArray? {
        val text = normalizeSearchQuery(query)
        if (text.isEmpty()) return null
        return try {
            synchronized(lock) { embedLocked(QUERY_PROMPT + text) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            warn("query encoding failed", error)
            null
        }
    }

    fun release() {
        synchronized(lock) {
            loaded?.session?.close()
            loaded = null
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) release()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun embedLocked(text: String): ByteArray {
        val model = load()
        val ids = model.vocab.encode(text)
        val idTensor = OnnxTensor.createTensor(model.env, arrayOf(ids))
        val maskTensor = OnnxTensor.createTensor(model.env, arrayOf(LongArray(ids.size) { 1L }))
        val typeTensor = OnnxTensor.createTensor(model.env, arrayOf(LongArray(ids.size)))
        try {
            val inputs = linkedMapOf<String, OnnxTensor>()
            if ("input_ids" in model.session.inputNames) inputs["input_ids"] = idTensor
            if ("attention_mask" in model.session.inputNames) inputs["attention_mask"] = maskTensor
            if ("token_type_ids" in model.session.inputNames) inputs["token_type_ids"] = typeTensor
            check(inputs.isNotEmpty()) { "onnx: no known inputs" }
            model.session.run(inputs).use { result ->
                val output = result.get(0) as OnnxTensor
                val dims = output.info.shape
                check(dims.size == 3 && dims[1] == ids.size.toLong() && dims[2] == HIDDEN_DIM.toLong()) {
                    "onnx: unexpected shape ${dims.contentToString()}"
                }
                val hidden = FloatArray(ids.size * HIDDEN_DIM)
                output.floatBuffer.get(hidden)
                val pooled = FloatArray(HIDDEN_DIM)
                for (token in ids.indices) {
                    val offset = token * HIDDEN_DIM
                    for (dim in 0 until HIDDEN_DIM) pooled[dim] += hidden[offset + dim]
                }
                val count = ids.size.toFloat()
                for (dim in pooled.indices) pooled[dim] /= count
                return projectQuantize(pooled, model.weight, model.bias)
            }
        } finally {
            idTensor.close()
            maskTensor.close()
            typeTensor.close()
        }
    }

    private fun load(): Loaded {
        loaded?.let { return it }
        val dir = explicitDir ?: installFromAssets()
        val vocab = WordPiece.load(File(dir, "vocab.txt"))
        val (weight, bias) = loadDense(File(dir, "dense.safetensors"))
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            val session = env.createSession(File(dir, "model_quantized.onnx").absolutePath, options)
            return Loaded(env, vocab, weight, bias, session).also { loaded = it }
        } finally {
            options.close()
        }
    }

    private fun installFromAssets(): File {
        val appContext = context ?: error("query model unavailable")
        val dest = File(appContext.filesDir, "leaf-ir-v1")
        val marker = File(dest, "revision")
        if (marker.isFile && marker.readText() == MODEL_REV && File(dest, "model_quantized.onnx").isFile) return dest
        dest.mkdirs()
        try {
            for (name in MODEL_FILES) {
                appContext.assets.open("leaf-ir-v1/$name").use { input ->
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

    private class Loaded(
        val env: OrtEnvironment,
        val vocab: WordPiece,
        val weight: FloatArray,
        val bias: FloatArray,
        val session: OrtSession,
    )

    private companion object {
        const val QUERY_PROMPT = "Represent this sentence for searching relevant passages: "
        const val MODEL_REV = "4262131b32c3182bd06e67e92ae69d7bd66e0c5c"
        val MODEL_FILES = listOf("vocab.txt", "dense.safetensors", "model_quantized.onnx", "model_quantized.onnx_data")

        fun warn(message: String, error: Throwable) {
            try {
                android.util.Log.w("LeafQueryEncoder", message, error)
            } catch (_: RuntimeException) {
                // Host unit tests link the Android stub, which throws on Log.
            }
        }
    }
}
