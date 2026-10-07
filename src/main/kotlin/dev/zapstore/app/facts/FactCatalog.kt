package dev.zapstore.app.facts

import dev.zapstore.iolite.SearchFact

/**
 * Labels for the privacy section.
 *
 * Yes shows [Pill.yesLabel] in the feature color. No shows [Pill.inverse] in the
 * inverse color, and a fact with no inverse is omitted. Permissions are a list, not pills.
 * Search matches its own phrases. These strings are display copy.
 */
object FactCatalog {
    data class Pill(
        val key: String,
        val yesLabel: String,
        val inverse: String?,
    ) {
        fun visible(yes: Boolean): Boolean = yes || inverse != null

        fun label(yes: Boolean): String = if (yes) yesLabel else checkNotNull(inverse)

        /** Yes is the feature color. No is the inverse color. */
        fun feature(yes: Boolean): Boolean = yes
    }

    private val pillByKey: Map<String, Pill> = listOf(
        Pill("google_services", "Google services", "No Google services"),
        Pill("tracking", "Tracking", null),
        Pill("ads", "Ads", null),
        Pill("offline_capable", "Works offline", null),
        Pill("e2ee", "E2EE", null),
        Pill("open_source", "Open source", "Closed source"),
    ).associateBy { it.key }

    private val permissionByKey: Map<String, String> = linkedMapOf(
        "microphone" to "Microphone",
        "camera" to "Camera",
        "coarse_location" to "Coarse location",
        "fine_location" to "Fine location",
        "contacts" to "Contacts",
        "read_sms" to "Read SMS",
        "receive_sms" to "Receive SMS",
        "send_sms" to "Send SMS",
        "call_log" to "Call log",
        "query_all_packages" to "Installed apps",
        "usage_stats" to "Usage access",
        "accessibility_service" to "Accessibility",
        "notification_listener" to "Notification access",
        "input_method" to "Keyboard",
        "request_install_packages" to "Install apps",
        "system_alert_window" to "Overlay",
        "device_admin" to "Device admin",
        "vpn_service" to "VPN",
    )

    fun pill(key: String): Pill? = pillByKey[key]

    fun permission(key: String): String? = permissionByKey[key]
}

/** Chip wording for a fact the search parser extracted. */
fun SearchFact.label(): String = when (this) {
    SearchFact.OpenSource -> FactCatalog.pill("open_source")!!.label(true)
    SearchFact.GoogleServices -> FactCatalog.pill("google_services")!!.label(false)
    SearchFact.WorksOffline -> FactCatalog.pill("offline_capable")!!.label(true)
}
