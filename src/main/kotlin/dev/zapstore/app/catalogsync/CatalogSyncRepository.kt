package dev.zapstore.app.catalogsync

import dev.zapstore.iolite.Iolite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CatalogSyncUiState(
    val epoch: Long = 0,
    val eventRowCount: Long = 0,
    val lastSyncedAtMillis: Long? = null,
    val syncing: Boolean = false,
    val error: String? = null,
    val availableUpdates: List<AvailableUpdate> = emptyList(),
)

class CatalogSyncRepository(
    private val database: ZapstoreDatabase,
    private val store: CompactEventStore,
    private val client: CatalogSyncClient,
    private val installedApps: () -> List<InstalledApp>,
    private val trustedPubkey: String,
    private val purpleQuartz: () -> Iolite?,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        CatalogSyncUiState(
            epoch = database.catalogState()?.epoch ?: 0,
            eventRowCount = database.eventRowCount(),
            availableUpdates = currentUpdates(),
        ),
    )
    val state: StateFlow<CatalogSyncUiState> = _state.asStateFlow()

    fun refreshUpdates() {
        _state.value = _state.value.copy(availableUpdates = currentUpdates())
    }

    suspend fun sync() {
        mutex.withLock {
            _state.value = _state.value.copy(syncing = true, error = null)
            try {
                applyRemoteUpdate()
                _state.value = readUiState(lastSyncedAtMillis = now(), error = null)
                purpleQuartz()?.invalidateLocalProjections()
            } catch (failure: Throwable) {
                _state.value = readUiState(
                    lastSyncedAtMillis = _state.value.lastSyncedAtMillis,
                    error = failure.message ?: failure::class.simpleName,
                )
            }
        }
    }

    private fun applyRemoteUpdate() {
        val current = database.catalogState() ?: error("catalog state is missing")
        val request = UpdatesRequest(
            protocol = CatalogSchema.PROTOCOL,
            catalog = current.catalog,
            schemaVersion = current.schemaVersion,
            epoch = current.epoch,
            searchModel = current.searchModel,
        )
        when (val response = client.requestUpdates(request)) {
            UpdatesResponse.NotModified -> Unit
            is UpdatesResponse.Delta -> applyDelta(response.body, current)
            is UpdatesResponse.Error -> throw CatalogVerificationException(
                response.reason ?: "catalog update failed with HTTP ${response.status}",
            )
        }
    }

    private fun applyDelta(body: ByteArray, current: CatalogState) {
        val envelope = CatalogEnvelope.decode(body)
        CatalogVerifier.verifyEnvelope(
            envelope,
            trustedPubkey = trustedPubkey,
            expectedKind = "delta",
            expectedOldEpoch = current.epoch,
            schemaVersion = current.schemaVersion,
            searchModel = current.searchModel,
            catalog = current.catalog,
        )
        val delta = CatalogEnvelope.parseDelta(envelope.payload)
        delta.events.forEach(CatalogVerifier::verifyEvent)
        store.applyDelta(
            events = delta.events,
            deletedIds = delta.deletedIds,
            next = current.copy(
                epoch = delta.newEpoch,
                generation = current.generation + 1,
            ),
        )
    }

    private fun readUiState(
        lastSyncedAtMillis: Long? = _state.value.lastSyncedAtMillis,
        error: String? = _state.value.error,
    ) = CatalogSyncUiState(
        epoch = database.catalogState()?.epoch ?: 0,
        eventRowCount = database.eventRowCount(),
        lastSyncedAtMillis = lastSyncedAtMillis,
        syncing = false,
        error = error,
        availableUpdates = currentUpdates(),
    )

    private fun currentUpdates(): List<AvailableUpdate> =
        LocalUpdateMatcher.match(installedApps(), catalogAssets())

    fun catalogAssets(): List<CatalogAssetRow> = database.withConnection { connection ->
        connection.prepare(
            """
            SELECT assets.app_id, COALESCE(apps.name, assets.app_id), COALESCE(assets.version, ''),
                   COALESCE(assets.version_code, 0), releases.channel, assets.platform, assets.variant,
                   assets.certificate_hash, assets.mime
            FROM assets
            LEFT JOIN apps ON apps.app_id = assets.app_id
            LEFT JOIN releases ON releases.app_id = assets.app_id AND releases.version = assets.version
            """.trimIndent(),
        ).use { statement ->
            val rows = mutableListOf<CatalogAssetRow>()
            while (statement.step()) {
                rows += CatalogAssetRow(
                    appId = statement.getText(0),
                    name = statement.getText(1),
                    version = statement.getText(2),
                    versionCode = statement.getLong(3),
                    channel = statement.nullableText(4),
                    platform = statement.nullableText(5),
                    variant = statement.nullableText(6),
                    certificateHash = statement.nullableText(7),
                    mime = statement.nullableText(8),
                )
            }
            rows
        }
    }

    private fun androidx.sqlite.SQLiteStatement.nullableText(index: Int): String? =
        if (isNull(index)) null else getText(index)
}
