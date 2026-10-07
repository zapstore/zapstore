package dev.zapstore.iolite

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import org.json.JSONObject

internal const val HIDDEN_DIM = 384

private const val QUANT_ABS = 0.3
private const val QUANT_MAX = 127

internal fun quantize(values: FloatArray): ByteArray {
    val scale = QUANT_ABS / QUANT_MAX
    return ByteArray(values.size) { index ->
        roundHalfAway(values[index] / scale).coerceIn(-QUANT_MAX, QUANT_MAX).toByte()
    }
}

internal fun loadDense(file: File): Pair<FloatArray, FloatArray> {
    val raw = file.readBytes()
    require(raw.size >= 8) { "dense: short file" }
    val headerLength = ByteBuffer.wrap(raw, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
    require(headerLength > 0 && 8 + headerLength <= raw.size) { "dense: bad header" }
    val headerEnd = (8 + headerLength).toInt()
    val header = JSONObject(raw.decodeToString(8, headerEnd))
    val weight = f32Tensor(header, raw, headerEnd, "linear.weight")
    val bias = f32Tensor(header, raw, headerEnd, "linear.bias")
    require(weight.size == HIDDEN_DIM * VECTOR_DIMS && bias.size == VECTOR_DIMS) {
        "dense: want ${HIDDEN_DIM}x$VECTOR_DIMS, got ${weight.size} + ${bias.size}"
    }
    return weight to bias
}

internal fun projectQuantize(pooled: FloatArray, weight: FloatArray, bias: FloatArray): ByteArray {
    val output = bias.copyOf()
    for (row in 0 until VECTOR_DIMS) {
        var sum = 0f
        val offset = row * HIDDEN_DIM
        for (column in 0 until HIDDEN_DIM) sum += pooled[column] * weight[offset + column]
        output[row] += sum
    }
    var norm = 0.0
    for (value in output) norm += value.toDouble() * value.toDouble()
    val length = sqrt(norm)
    if (length != 0.0) {
        val inverse = (1.0 / length).toFloat()
        for (index in output.indices) output[index] *= inverse
    }
    return quantize(output)
}

private fun f32Tensor(header: JSONObject, raw: ByteArray, bodyStart: Int, name: String): FloatArray {
    val meta = header.getJSONObject(name)
    require(meta.getString("dtype") == "F32") { "dense: $name dtype" }
    val offsets = meta.getJSONArray("data_offsets")
    val start = offsets.getInt(0)
    val end = offsets.getInt(1)
    val bytes = raw.copyOfRange(bodyStart + start, bodyStart + end)
    val out = FloatArray(bytes.size / 4)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    for (index in out.indices) out[index] = buffer.float
    return out
}

/** Matches Go math.Round: ties move away from zero. */
private fun roundHalfAway(value: Double): Int = when {
    value.isNaN() -> 0
    value >= 0 -> floor(value + 0.5).toInt()
    else -> ceil(value - 0.5).toInt()
}
