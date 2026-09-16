package dev.zapstore.iolite

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object QueryFingerprint {
    private const val FORMAT_VERSION = 1

    fun create(
        filters: List<Filter>,
        relays: Set<NormalizedRelayUrl>,
    ): String {
        val encodedFilters = filters.map(::encodeFilter).sortedWith(::compareBytes)
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(encodedFilters.size)
                encodedFilters.forEach { filter ->
                    output.writeInt(filter.size)
                    output.write(filter)
                }
                output.writeStrings(relays.map { it.url }.sorted())
            }
            bytes.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun encodeFilter(filter: Filter): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeNullableStrings(filter.ids)
                output.writeNullableStrings(filter.authors)
                output.writeNullableInts(filter.kinds)
                output.writeNullableStringMap(filter.tags)
                output.writeNullableStringMap(filter.tagsAll)
                output.writeNullableLong(filter.since)
                output.writeNullableLong(filter.until)
                output.writeNullableInt(filter.limit)
                output.writeNullableString(filter.search)
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeNullableStrings(values: List<String>?) {
        if (values == null) {
            writeInt(-1)
        } else {
            writeStrings(values.sorted())
        }
    }

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach { value -> writeString(value) }
    }

    private fun DataOutputStream.writeNullableInts(values: List<Int>?) {
        if (values == null) {
            writeInt(-1)
        } else {
            writeInt(values.size)
            values.sorted().forEach(::writeInt)
        }
    }

    private fun DataOutputStream.writeNullableStringMap(values: Map<String, List<String>>?) {
        if (values == null) {
            writeInt(-1)
        } else {
            writeInt(values.size)
            values.toSortedMap().forEach { (key, entries) ->
                writeString(key)
                writeStrings(entries.sorted())
            }
        }
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        val commonLength = minOf(left.size, right.size)
        for (index in 0 until commonLength) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}
