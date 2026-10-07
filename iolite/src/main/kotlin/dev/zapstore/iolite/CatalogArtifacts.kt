package dev.zapstore.iolite

/**
 * Artifact members of a catalog bundle. App files are `<app-id>/about`, `security`, `facts`,
 * `vector`, and `icon.webp`. Avatars are `<64 hex characters>.webp`.
 * A missing about, security, or facts member means that note did not change.
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
    val facts: String?,
    val about: String?,
    val security: String?,
)

internal data class AvatarArtifact(
    val pubkey: String,
    val webp: ByteArray,
)

internal object CatalogArtifacts {
    private const val MAX_APP_ID = 255

    fun fromMembers(members: Map<String, TarMember>, maxIconBytes: Long): CatalogArtifact {
        val apps = LinkedHashMap<String, Partial>()
        val avatars = ArrayList<AvatarArtifact>()
        for ((name, member) in members) {
            if (name == "manifest.json" || name == "diff.jsonl" || name == "index") continue
            val pubkey = avatarPubkey(name)
            if (pubkey != null) {
                if (member.data.isEmpty() || member.data.size.toLong() > maxIconBytes) {
                    fail("bad avatar $name")
                }
                avatars += AvatarArtifact(pubkey, member.data)
                continue
            }
            val slash = name.indexOf('/')
            if (slash <= 0 || slash != name.lastIndexOf('/')) fail("unexpected catalog member $name")
            val appId = name.substring(0, slash)
            val file = name.substring(slash + 1)
            if (!validAppId(appId) || file !in APP_FILES) fail("unexpected catalog member $name")
            val app = apps.getOrPut(appId) { Partial(appId) }
            when (file) {
                "about", "security", "facts" -> applyText(app, file, member.data)
                "vector" -> {
                    if (member.data.size != VECTOR_DIMS) fail("vector $appId")
                    app.vector = member.data
                }
                "icon.webp" -> {
                    if (member.data.isEmpty() || member.data.size.toLong() > maxIconBytes) fail("icon $appId exceeds size limit")
                    app.webp = member.data
                }
            }
        }
        return CatalogArtifact(
            apps = apps.values.map { it.toArtifact() },
            avatars = avatars,
        )
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

    private fun applyText(app: Partial, file: String, raw: ByteArray) {
        val text = raw.toString(Charsets.UTF_8)
        if (text.any { it == '\u0000' }) fail("$file ${app.appId} contains NUL")
        when (file) {
            "about" -> app.about = text
            "security" -> app.security = text
            "facts" -> app.facts = text
        }
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

    private fun fail(reason: String): Nothing = throw CatalogImportException(reason)

    private class Partial(val appId: String) {
        var webp: ByteArray? = null
        var vector: ByteArray? = null
        var facts: String? = null
        var about: String? = null
        var security: String? = null

        fun toArtifact() = AppArtifact(appId, webp, vector, facts, about, security)
    }

    private val APP_FILES = setOf("about", "security", "facts", "vector", "icon.webp")
}
