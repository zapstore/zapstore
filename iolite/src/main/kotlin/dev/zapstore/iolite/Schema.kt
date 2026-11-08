package dev.zapstore.iolite

internal object Schema {
    /** A database lacking any column of [statements] is rebuilt on open regardless, so adding columns needs no bump. */
    const val USER_VERSION = 1

    const val APPS_SEARCH = """
        CREATE TABLE apps_search (
            id          BLOB PRIMARY KEY REFERENCES apps(id) ON DELETE CASCADE,
            vector      BLOB,
            keywords    TEXT NOT NULL DEFAULT '',
            CHECK (vector IS NULL OR length(vector) = 768)
        )
    """

    val statements: List<String> = listOf(
        // Last successful relay refresh per query fingerprint; backs QueryOptions.cachedFor.
        """
        CREATE TABLE query_refresh (
            fingerprint     TEXT PRIMARY KEY,
            refreshed_at    INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE catalogs (
            -- Never reused, so an icon folder named by id cannot belong to a later catalog.
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            relay_url   TEXT,
            position    INTEGER UNIQUE,
            is_private  INTEGER NOT NULL DEFAULT 0 CHECK (is_private IN (0, 1)),
            manifest_pubkey BLOB UNIQUE,
            epoch       INTEGER NOT NULL DEFAULT 0,
            endpoints   TEXT NOT NULL DEFAULT '[]',
            hints       TEXT NOT NULL DEFAULT '[]'
        )
        """.trimIndent(),
        """
        CREATE TABLE apps (
            -- SHA-256 of the raw manifest pubkey, a NUL, and the UTF-8 app id.
            id                          BLOB PRIMARY KEY,
            catalog_id                  INTEGER NOT NULL
                                            REFERENCES catalogs(id) ON DELETE CASCADE,
            app_id                      TEXT NOT NULL,
            certificate_hash            BLOB,
            app_event_created_at        INTEGER NOT NULL,
            pubkey                      BLOB,
            event_pubkey                BLOB NOT NULL,
            name                        TEXT NOT NULL,
            summary                     TEXT NOT NULL DEFAULT '',
            repository                  TEXT,
            version                     TEXT NOT NULL,
            version_code                INTEGER NOT NULL,
            channel                     TEXT,
            metadata                    TEXT NOT NULL DEFAULT '{}',
            about                       TEXT NOT NULL DEFAULT '',
            security                    TEXT NOT NULL DEFAULT '',
            facts                       TEXT NOT NULL DEFAULT '',
            -- Explicit fact values used by search. Bit order is FactBits in QueryParse.kt.
            fact_bits                   INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX apps_identity_idx ON apps(catalog_id, app_id)",
        "CREATE INDEX apps_pubkey_idx ON apps(pubkey)",
        "CREATE INDEX apps_event_pubkey_idx ON apps(event_pubkey)",
        "CREATE INDEX apps_updated_idx ON apps(app_event_created_at DESC)",
        "CREATE INDEX apps_visible_idx ON apps(app_id)",
        "CREATE INDEX apps_version_idx ON apps(app_id, version_code DESC)",
        """
        CREATE TABLE certificate_proofs (
            -- SHA-256 of the raw manifest pubkey, certificate hash, and proof pubkey, separated by NULs.
            id                  BLOB PRIMARY KEY,
            catalog_id          INTEGER NOT NULL
                                  REFERENCES catalogs(id) ON DELETE CASCADE,
            certificate_hash    BLOB NOT NULL,
            pubkey              BLOB NOT NULL,
            event_id            BLOB NOT NULL,
            created_at          INTEGER NOT NULL,
            expiry              INTEGER NOT NULL,
            revoked             INTEGER NOT NULL DEFAULT 0
                                  CHECK (revoked IN (0, 1)),
            delegations         TEXT NOT NULL DEFAULT '[]',
            -- The `signature` tag; empty on a revocation. Checked against the APK before install.
            signature           TEXT NOT NULL DEFAULT '',
            UNIQUE (catalog_id, certificate_hash, pubkey)
        )
        """.trimIndent(),
        "CREATE INDEX certificate_proofs_certificate_idx ON certificate_proofs(catalog_id, certificate_hash)",
        """
        CREATE TABLE preferences (
            identifier  TEXT PRIMARY KEY,
            updated_at  INTEGER NOT NULL,
            metadata    TEXT NOT NULL DEFAULT '{}'
        )
        """.trimIndent(),
        APPS_SEARCH.trimIndent(),
        """
        CREATE TABLE comments (
            event_id            BLOB PRIMARY KEY,
            pubkey              BLOB NOT NULL,
            created_at          INTEGER NOT NULL,
            content             TEXT NOT NULL,
            app_id              TEXT,
            -- The NIP-22 root coordinate (`A`): an app's or a stack's.
            root                TEXT,
            parent_event_id     BLOB,
            -- The `v` tag: the app version the comment was written on.
            version             TEXT
        ) WITHOUT ROWID
        """.trimIndent(),
        "CREATE INDEX comments_app_idx ON comments(app_id, created_at DESC)",
        "CREATE INDEX comments_root_idx ON comments(root, created_at DESC)",
        "CREATE INDEX comments_parent_idx ON comments(parent_event_id)",
        """
        CREATE TABLE zaps (
            event_id        BLOB PRIMARY KEY,
            -- The sender: the zap request's pubkey, not the receipt's.
            pubkey          BLOB NOT NULL,
            amount_sats     INTEGER NOT NULL CHECK (amount_sats > 0),
            created_at      INTEGER NOT NULL,
            app_id          TEXT NOT NULL,
            -- The zap request's content; NULL when blank.
            note            TEXT
        ) WITHOUT ROWID
        """.trimIndent(),
        "CREATE INDEX zaps_app_idx ON zaps(app_id, created_at DESC)",
        "CREATE INDEX zaps_pubkey_idx ON zaps(pubkey, created_at DESC)",
        """
        CREATE TABLE stacks (
            pubkey          BLOB NOT NULL,
            identifier      TEXT NOT NULL,
            event_id        BLOB NOT NULL,
            name            TEXT NOT NULL,
            description     TEXT,
            public_apps     TEXT NOT NULL DEFAULT '[]',
            updated_at      INTEGER NOT NULL,
            metadata        TEXT NOT NULL DEFAULT '{}',
            PRIMARY KEY (pubkey, identifier)
        )
        """.trimIndent(),
        "CREATE INDEX stacks_updated_idx ON stacks(updated_at DESC)",
        """
        CREATE TABLE profiles (
            pubkey              BLOB PRIMARY KEY,
            updated_at          INTEGER NOT NULL,
            name                TEXT,
            picture_url         TEXT,
            metadata            TEXT NOT NULL DEFAULT '{}'
        )
        """.trimIndent(),
        """
        CREATE TABLE relay_reports (
            relay_url       TEXT NOT NULL,
            monitor         BLOB NOT NULL,
            created_at      INTEGER NOT NULL,
            payment         INTEGER NOT NULL DEFAULT 0 CHECK (payment IN (0, 1)),
            PRIMARY KEY (relay_url, monitor)
        )
        """.trimIndent(),
        """
        CREATE TABLE publish_queue (
            event_id        BLOB PRIMARY KEY,
            event_json      TEXT NOT NULL
        )
        """.trimIndent(),
    )
}
