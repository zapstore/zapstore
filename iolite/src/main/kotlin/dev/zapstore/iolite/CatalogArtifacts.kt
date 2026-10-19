package dev.zapstore.iolite

/**
 * Artifact members of a catalog bundle. App files are `<app-id>/about`, `security`,
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
        val apps = LinkedHashMap<String, Partial>()
        val avatars = ArrayList<AvatarArtifact>()
        for ((name, member) in members) {
            if (name == "manifest.json" || name == "diff.jsonl" || name == "index") continue
            val pubkey = avatarPubkey(name)
            if (pubkey != null) {
                if (member.data.isEmpty() || member.data.size.toLong() > maxIconBytes) {
                    skipped += "bad avatar $name"
                } else {
                    avatars += AvatarArtifact(pubkey, member.data)
                }
                continue
            }
            val slash = name.indexOf('/')
            val appId = if (slash > 0 && slash == name.lastIndexOf('/')) name.substring(0, slash) else null
            val file = name.substring(slash + 1)
            if (appId == null || !validAppId(appId) || file !in APP_FILES) {
                skipped += "unexpected catalog member $name"
                continue
            }
            val problem = when (file) {
                "about", "security" -> "$file $appId contains NUL".takeIf { member.data.any { it == 0.toByte() } }
                "vector" -> "vector $appId has the wrong size".takeIf { member.data.size != VECTOR_DIMS }
                else -> "icon $appId is empty or too large".takeIf { member.data.isEmpty() || member.data.size.toLong() > maxIconBytes }
            }
            if (problem != null) {
                skipped += problem
                continue
            }
            val app = apps.getOrPut(appId) { Partial(appId) }
            when (file) {
                "about" -> app.about = member.data.toString(Charsets.UTF_8)
                "security" -> app.security = member.data.toString(Charsets.UTF_8)
                "vector" -> app.vector = member.data
                "icon.webp" -> app.webp = member.data
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

        fun toArtifact() = AppArtifact(appId, webp, vector, about, security)
    }

    private val APP_FILES = setOf("about", "security", "vector", "icon.webp")
}
