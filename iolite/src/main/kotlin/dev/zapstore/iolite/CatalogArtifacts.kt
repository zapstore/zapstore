package dev.zapstore.iolite

import java.io.InputStream

/**
 * Artifact members of a catalog bundle. App files are `<app-id>/about`, `security`, `trust`,
 * `vector`, and `icon.webp`. Avatars are `<64 hex characters>.webp`.
 * A missing about or security member means that note did not change.
 * The stored text stays. A missing vector or icon does the same.
 */
internal data class CatalogArtifact(
    val apps: List<AppArtifact> = emptyList(),
    val avatars: List<AvatarArtifact> = emptyList(),
) {
    companion object {
        val EMPTY = CatalogArtifact()
    }
}

internal data class AppArtifact(
    val appId: String,
    val webp: ByteArray?,
    val vector: ByteArray?,
    val about: String?,
    val security: String?,
    /** Raw `trust` line, already checked to parse. */
    val trust: String? = null,
)

internal data class AvatarArtifact(
    val pubkey: String,
    val webp: ByteArray,
)

internal object CatalogArtifacts {
    private const val MAX_APP_ID = 255

    /** Artifacts from verified members. A member that is not a valid artifact is skipped. */
    fun fromMembers(
        members: Map<String, TarMember>,
        maxIconBytes: Long,
        skipped: MutableList<String> = ArrayList(),
    ): CatalogArtifact {
        val apps = ArrayList<AppArtifact>()
        val avatars = ArrayList<AvatarArtifact>()
        val reader = Reader(maxIconBytes, skipped) { artifact -> apps += artifact.apps; avatars += artifact.avatars }
        for ((name, member) in members) reader.add(name, member.data.size.toLong(), member.data.inputStream())
        reader.finish()
        return CatalogArtifact(apps = apps, avatars = avatars)
    }

    /**
     * Turns artifact members into artifacts as the tar streams past. An app's files are adjacent in tar
     * order, so only the current app is held; it goes to [emit] when the next app starts. A member is
     * checked for size before it is read, so a bad one never reaches memory.
     */
    class Reader(
        private val maxIconBytes: Long,
        private val skipped: MutableList<String>,
        private val emit: (CatalogArtifact) -> Unit,
    ) {
        private var current: Partial? = null

        fun add(name: String, size: Long, data: InputStream) {
            if (name == "manifest.json" || name == "diff.jsonl" || name == "index") return
            val pubkey = avatarPubkey(name)
            if (pubkey != null) {
                if (size == 0L || size > maxIconBytes) {
                    skipped += "bad avatar $name"
                } else {
                    emit(CatalogArtifact(avatars = listOf(AvatarArtifact(pubkey, data.readExactly(size.toInt())))))
                }
                return
            }
            val slash = name.indexOf('/')
            val appId = if (slash > 0 && slash == name.lastIndexOf('/')) name.substring(0, slash) else null
            val file = name.substring(slash + 1)
            if (appId == null || !validAppId(appId) || file !in APP_FILES) {
                skipped += "unexpected catalog member $name"
                return
            }
            val tooLarge = when (file) {
                "trust" -> "trust $appId is not a trust line".takeIf { size > MAX_TRUST_BYTES }
                "about", "security" -> "$file $appId is too large".takeIf { size > MAX_TEXT_BYTES }
                "vector" -> "vector $appId has the wrong size".takeIf { size != VECTOR_DIMS.toLong() }
                else -> "icon $appId is empty or too large".takeIf { size == 0L || size > maxIconBytes }
            }
            if (tooLarge != null) {
                skipped += tooLarge
                return
            }
            val bytes = data.readExactly(size.toInt())
            val problem = when (file) {
                "trust" -> "trust $appId is not a trust line".takeIf { Trust.parse(bytes.toString(Charsets.UTF_8)) == null }
                "about", "security" -> "$file $appId contains NUL".takeIf { bytes.any { it == 0.toByte() } }
                else -> null
            }
            if (problem != null) {
                skipped += problem
                return
            }
            val app = current?.takeIf { it.appId == appId } ?: Partial(appId).also { finish(); current = it }
            when (file) {
                "about" -> app.about = bytes.toString(Charsets.UTF_8)
                "security" -> app.security = bytes.toString(Charsets.UTF_8)
                "trust" -> app.trust = bytes.toString(Charsets.UTF_8).trim()
                "vector" -> app.vector = bytes
                "icon.webp" -> app.webp = bytes
            }
        }

        /** Hands over the app still being read. */
        fun finish() {
            val app = current ?: return
            current = null
            emit(CatalogArtifact(apps = listOf(app.toArtifact())))
        }
    }

    fun allowedMember(name: String): Boolean {
        if (name == "diff.jsonl" || name == "index") return true
        if (avatarPubkey(name) != null) return true
        val slash = name.indexOf('/')
        if (slash <= 0 || slash != name.lastIndexOf('/')) return false
        val appId = name.substring(0, slash)
        val file = name.substring(slash + 1)
        return validAppId(appId) && file in APP_FILES
    }

    private fun avatarPubkey(name: String): String? {
        if (name.contains('/')) return null
        val pubkey = name.removeSuffix(".webp")
        if (pubkey.length == name.length) return null
        if (!Hex.isHex(pubkey, 64)) return null
        return pubkey
    }

    private fun validAppId(id: String): Boolean =
        id.length in 1..MAX_APP_ID && id.none { it == '\u0000' || it == '/' || it == '\\' } && ".." !in id

    private class Partial(val appId: String) {
        var webp: ByteArray? = null
        var vector: ByteArray? = null
        var about: String? = null
        var security: String? = null
        var trust: String? = null

        fun toArtifact() = AppArtifact(appId, webp, vector, about, security, trust)
    }

    private const val MAX_TRUST_BYTES = 4_096
    /** `about` and `security`. The largest in production is under 5 KB. */
    private const val MAX_TEXT_BYTES = 64 * 1024
    private val APP_FILES = setOf("about", "security", "trust", "vector", "icon.webp")
}
