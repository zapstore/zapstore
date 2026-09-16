package dev.zapstore.app.catalogsync

object CatalogSchema {
    const val VERSION = 1
    const val PROTOCOL = 1
    const val CATALOG = "default"
    const val SEARCH_MODEL = "leaf-ir-v1"
    const val DATABASE_NAME = "zapstore.db"
    const val BUNDLED_ASSET = "catalog.db"
    const val MANIFEST_KIND = 30_078
    const val CONTENT_TYPE_DELTA = "application/vnd.zapstore.catalog-delta"
    val MAGIC = byteArrayOf('Z'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

    val CREATE_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS events (
            id BLOB PRIMARY KEY,
            pubkey BLOB NOT NULL,
            created_at INTEGER NOT NULL,
            kind INTEGER NOT NULL,
            d_tag TEXT NOT NULL DEFAULT '',
            content TEXT NOT NULL,
            tags TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS events_kind_time ON events(kind, created_at DESC, id)",
        "CREATE INDEX IF NOT EXISTS events_pubkey_kind_time ON events(pubkey, kind, created_at DESC, id)",
        "CREATE INDEX IF NOT EXISTS events_kind_d ON events(kind, pubkey, d_tag)",
        """
        CREATE TABLE IF NOT EXISTS event_tags (
            event_id BLOB NOT NULL,
            key TEXT NOT NULL,
            value TEXT NOT NULL,
            PRIMARY KEY (key, value, event_id),
            FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS event_tags_event ON event_tags(event_id)",
        """
        CREATE TABLE IF NOT EXISTS catalog_state (
            catalog TEXT PRIMARY KEY,
            epoch INTEGER NOT NULL,
            schema_version INTEGER NOT NULL,
            search_model TEXT NOT NULL,
            generation INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS apps (
            app_id TEXT PRIMARY KEY,
            event_id BLOB NOT NULL,
            pubkey BLOB NOT NULL,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS releases (
            app_id TEXT NOT NULL,
            version TEXT NOT NULL,
            event_id BLOB NOT NULL,
            channel TEXT,
            created_at INTEGER NOT NULL,
            PRIMARY KEY (app_id, version)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS assets (
            event_id BLOB PRIMARY KEY,
            app_id TEXT NOT NULL,
            version_code INTEGER,
            version TEXT,
            mime TEXT,
            platform TEXT,
            variant TEXT,
            certificate_hash TEXT,
            file_hash TEXT,
            url TEXT,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS assets_app ON assets(app_id, version_code)",
    )
}
