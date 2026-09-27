package dev.zapstore.app.search

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeafQueryEncoderTest {
    @Test
    fun wordPieceSplitsKnownPieces() {
        val dir = File("build/tmp/vocab-${System.nanoTime()}").apply { mkdirs() }
        val vocab = File(dir, "vocab.txt")
        vocab.writeText("[PAD]\n[unused0]\n[UNK]\n[CLS]\n[SEP]\nzap\n##store\napp\n")
        val ids = WordPiece.load(vocab).encode("Zapstore app")
        assertEquals(longArrayOf(3, 5, 6, 7, 4).toList(), ids.toList())
    }

    @Test
    fun quantizeClampsToInt8() {
        val got = quantize(floatArrayOf(-0.3f, 0f, 0.3f, 0.6f, -0.6f))
        assertArrayEquals(byteArrayOf(-127, 0, 127, 127, -127), got)
    }

    @Test
    fun blankQuerySkipsTheModel() {
        val encoder = LeafQueryEncoder(File("missing-leaf-model"))
        assertNull(encoder.encode("   "))
    }

    @Test
    fun queryVectorMatchesSealer() {
        val dir = listOf(File(".tools/leaf-ir-assets/leaf-ir-v1"), File("android/.tools/leaf-ir-assets/leaf-ir-v1"))
            .first { it.resolve("model_quantized.onnx").isFile }
        val encoder = LeafQueryEncoder(dir)
        try {
            val got = encoder.encode("offline maps")
            val want = javaClass.getResourceAsStream("/leaf-ir-offline-maps.bin")!!.readBytes()
            assertArrayEquals(want, got)
        } finally {
            encoder.release()
        }
    }
}
