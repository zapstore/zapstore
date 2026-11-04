package dev.zapstore.app.facts

import dev.zapstore.iolite.SearchFact

/**
 * Labels for the privacy section, per `product/specs/security.md`.
 *
 * Yes shows [Pill.yesLabel]; no shows [Pill.inverse], and a fact with no inverse is omitted.
 * [Pill.tone] says whether the shown value is good or bad for the person installing.
 * Permissions are a list, not pills.
 * Search matches its own phrases. These strings are display copy.
 */
object FactCatalog {
    enum class Tone { Good, Bad, Neutral }

    data class Pill(
        val key: String,
        val yesLabel: String,
        val inverse: String?,
        /** True when yes is good for the user, false when it is bad, null when neither. */
        val yesIsGood: Boolean? = null,
    ) {
        fun visible(yes: Boolean): Boolean = yes || inverse != null

        fun label(yes: Boolean): String = if (yes) yesLabel else checkNotNull(inverse)

        fun tone(yes: Boolean): Tone = when (yesIsGood) {
            null -> Tone.Neutral
            yes -> Tone.Good
            else -> Tone.Bad
        }
    }

    /** A permission's fact, its short label, and what it lets an app do in plain words. */
    data class Permission(val fact: String, val label: String, val does: String)

    private val pillByKey: Map<String, Pill> = listOf(
        Pill("google_services", "Google services", "No Google services", yesIsGood = false),
        Pill("tracking", "Shares data", null, yesIsGood = false),
        Pill("ads", "Ads", null, yesIsGood = false),
        Pill("offline_capable", "Works offline", null, yesIsGood = true),
        Pill("source_available", "Source available", "No source", yesIsGood = true),
    ).associateBy { it.key }

    private val permissions: List<Pair<Permission, List<String>>> = listOf(
        Permission("microphone", "Microphone", "Record audio") to listOf("RECORD_AUDIO"),
        Permission("camera", "Camera", "Take photos and record video") to listOf("CAMERA"),
        Permission("coarse_location", "Coarse location", "See your approximate location") to listOf("ACCESS_COARSE_LOCATION"),
        Permission("fine_location", "Fine location", "See your precise location") to listOf("ACCESS_FINE_LOCATION"),
        Permission("contacts", "Contacts", "Read and change your contacts") to listOf("READ_CONTACTS", "WRITE_CONTACTS"),
        Permission("read_sms", "Read SMS", "Read your text messages") to listOf("READ_SMS"),
        Permission("receive_sms", "Receive SMS", "Receive text messages") to listOf("RECEIVE_SMS"),
        Permission("send_sms", "Send SMS", "Send text messages") to listOf("SEND_SMS"),
        Permission("call_log", "Call log", "Read your call history") to listOf("READ_CALL_LOG"),
        Permission("query_all_packages", "Installed apps", "See every app installed on this phone") to listOf("QUERY_ALL_PACKAGES"),
        Permission("usage_stats", "Usage access", "See how you use other apps") to listOf("PACKAGE_USAGE_STATS"),
        Permission("accessibility_service", "Accessibility", "Read the screen and act for you") to listOf("BIND_ACCESSIBILITY_SERVICE"),
        Permission("notification_listener", "Notification access", "Read your notifications") to listOf("BIND_NOTIFICATION_LISTENER_SERVICE"),
        Permission("input_method", "Keyboard", "Act as your keyboard and see what you type") to listOf("BIND_INPUT_METHOD"),
        Permission("request_install_packages", "Install apps", "Install other apps") to listOf("REQUEST_INSTALL_PACKAGES"),
        Permission("system_alert_window", "Overlay", "Draw over other apps") to listOf("SYSTEM_ALERT_WINDOW"),
        Permission("device_admin", "Device admin", "Manage this device, such as locking it") to listOf("BIND_DEVICE_ADMIN"),
        Permission("vpn_service", "VPN", "Route all network traffic through itself") to listOf("BIND_VPN_SERVICE"),
    )
    private val permissionByFact = permissions.associate { (permission, _) -> permission.fact to permission }
    private val permissionById = permissions.flatMap { (permission, ids) -> ids.map { it to permission } }.toMap()

    private val flagLabels = mapOf(
        "source_mismatch" to "Differs from source",
        "no_english" to "No English",
    )

    fun pill(key: String): Pill? = pillByKey[key]

    /** Label for a permission fact, such as `camera`. */
    fun permission(fact: String): String? = permissionByFact[fact]?.label

    /** What the permission for a fact lets an app do, such as `Take photos and record video`. */
    fun permissionDoes(fact: String): String? = permissionByFact[fact]?.does

    /** Fact and label for an Android permission id, such as `CAMERA`. Null for an id not in the table. */
    fun permissionForId(id: String): Permission? = permissionById[id]

    /** Badge label for a flag id. Null for an id the app does not show. */
    fun flag(id: String): String? = flagLabels[id]
}

/** Chip wording for a fact the search parser extracted. */
fun SearchFact.label(): String = when (this) {
    SearchFact.SourceAvailable -> FactCatalog.pill("source_available")!!.label(true)
    SearchFact.GoogleServices -> FactCatalog.pill("google_services")!!.label(false)
    SearchFact.WorksOffline -> FactCatalog.pill("offline_capable")!!.label(true)
}
