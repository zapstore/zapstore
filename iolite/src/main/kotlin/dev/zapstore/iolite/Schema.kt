package dev.zapstore.iolite

internal object Schema {
    const val USER_VERSION = 3

    const val APPS_SEARCH = """
        CREATE TABLE apps_search (
            id          BLOB PRIMARY KEY REFERENCES apps(id) ON DELETE CASCADE,
            vector   BLOB,
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
            id          INTEGER PRIMARY KEY,
            relay_url   TEXT NOT NULL UNIQUE,
            position    INTEGER UNIQUE,
            is_private  INTEGER NOT NULL DEFAULT 0 CHECK (is_private IN (0, 1)),
            manifest_pubkey BLOB,
            epoch       INTEGER NOT NULL DEFAULT 0,
            endpoints   TEXT NOT NULL DEFAULT '[]'
        )
        """.trimIndent(),
        """
        CREATE TABLE apps (
            -- SHA-256 of the raw manifest pubkey, a NUL, and the UTF-8 app id.
            id                          BLOB PRIMARY KEY,
            catalog_id                  INTEGER NOT NULL
                                            REFERENCES catalogs(id) ON DELETE CASCADE,
            app_id                      TEXT NOT NULL,
            variant_id                  BLOB,
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
            UNIQUE (catalog_id, certificate_hash, pubkey)
        )
        """.trimIndent(),
        "CREATE INDEX certificate_proofs_certificate_idx ON certificate_proofs(catalog_id, certificate_hash)",
        """
        CREATE TABLE app_variants (
            id                  BLOB PRIMARY KEY,
            app_id              BLOB NOT NULL
                                  REFERENCES apps(id) ON DELETE CASCADE,
            channel             TEXT NOT NULL DEFAULT '',
            variant             TEXT NOT NULL DEFAULT '',
            certificate_hash    BLOB NOT NULL,
            version             TEXT NOT NULL,
            version_code        INTEGER NOT NULL,
            metadata            TEXT NOT NULL DEFAULT '{}',
            UNIQUE (app_id, channel, variant),
            CHECK (channel <> '' OR variant <> '')
        )
        """.trimIndent(),
        "CREATE INDEX app_variants_version_idx ON app_variants(app_id, channel, version_code DESC)",
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
            stack               TEXT,
            parent_event_id     BLOB,
            metadata            TEXT NOT NULL DEFAULT '{}'
        )
        """.trimIndent(),
        "CREATE INDEX comments_app_idx ON comments(app_id, created_at DESC)",
        "CREATE INDEX comments_stack_idx ON comments(stack, created_at DESC)",
        "CREATE INDEX comments_parent_idx ON comments(parent_event_id)",
        """
        CREATE TABLE zaps (
            event_id        BLOB PRIMARY KEY,
            pubkey          BLOB NOT NULL,
            amount_sats     INTEGER NOT NULL CHECK (amount_sats > 0),
            created_at      INTEGER NOT NULL,
            app_id          TEXT NOT NULL,
            metadata        TEXT NOT NULL DEFAULT '{}'
        )
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
        CREATE TABLE outbox (
            event_id        BLOB PRIMARY KEY,
            event_json      TEXT NOT NULL
        )
        """.trimIndent(),
    )
}
