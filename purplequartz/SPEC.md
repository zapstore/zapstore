# purplequartz — v1 Specification

**Status:** Implementation-ready  
**Created:** 2026-07-11  
**Revised:** 2026-07-11  
**Target:** Version 1  
**Platform:** Android API 29+  
**Library/module name:** `purplequartz`  
**Android namespace / Kotlin package:** `dev.zapstore.purplequartz`  
**Kotlin façade:** `PurpleQuartz`

## 1. Goal

`purplequartz` is a small Android wrapper around Quartz. Version 1 is focused on one default application-facing invariant:

> Local and local-and-remote queries return persistent events through the local Quartz event store. Remote-only queries are the explicit exception and may emit validated relay events directly while also saving persistent events locally.

The primary public API is a reactive `query` inspired by the query behavior in `../../models`. Quartz `Filter` and `Event` types are sufficient; v1 does not add a generic model layer.

Version 1 uses caller-supplied fixed relay sets. Amethyst's NIP-65 outbox routing is application policy built above Quartz, not a generic Quartz query API. Reproducing it would violate the goal of a small wrapper and is deferred.

```text
Local / LocalAndRemote:
query
    -> install local observation
    -> emit the permitted local seed
    -> optionally start relay REQ
    -> verify each relay event
    -> commit it to the local event store
    -> observe the committed store change
    -> emit an updated QueryState

Remote:
query
    -> start relay REQ
    -> verify each relay event
    -> emit the event directly
    -> also enqueue persistent events for local storage
```

Version 1 must make the local-first path small, explicit, and difficult to bypass.

## 2. Quartz dependency boundary

Production code pins:

```text
com.vitorpamplona.quartz:quartz:1.12.6
```

Gradle selects its published Android variant, `quartz-android:1.12.6`. The dependency is available from Maven Central. The implementation toolchain is JDK 21, Gradle 9.4.1, Android Gradle Plugin 9.2.1, Kotlin 2.4.0, compile SDK 37, and minimum SDK 29.

`purplequartz` uses Quartz 1.12.6 public APIs for:

- Nostr event and filter types;
- NIP-01 ID and signature validation;
- relay connections, REQs, EOSE, and reconnect/resubscription;
- `EventStore` and `ObservableEventStore`;
- replaceable/addressable event, deletion, expiration, and vanish semantics;
- NIP-50 local full-text search;
- normalized relay URLs and WebSocket construction.

Public event, filter, and normalized relay URL types come directly from Quartz. `purplequartz` must not create parallel protocol models.

Production and tests resolve the pinned published artifact. The cloned `reference/amethyst` tree is research-only and must not be included as a Gradle project, composite build, source dependency, or runtime resource.

### 2.1 Verified public integration surface

Version 1 is implemented only through these supported public APIs:

- `NostrClient(WebsocketBuilder, CoroutineScope)`;
- `INostrClient.subscribe`, `unsubscribe`, connection listeners, and lifecycle methods;
- `SubscriptionListener`, including EVENT, EOSE, CLOSED, connection failure, and subscription-start callbacks;
- `Event.verifyId()`, `Event.verifySignature()`, and NIP-40 `Event.isExpired()`;
- `EventStore`, `IEventStore`, `ObservableEventStore`, and `ObservableEventStore.changes`;
- `Filter`, `Event`, `NormalizedRelayUrl`, and `BasicOkHttpWebSocket.Builder`.

The wrapper must not use `subscribeAsFlow`: that helper hides EOSE and connection-generation boundaries needed by this contract.

### 2.2 Dependency compatibility gate

Before feature implementation, a compile-and-runtime compatibility test must prove that the published Android artifact:

1. resolves from Maven Central in an API-29 Android library/consumer;
2. exposes every public API listed in section 2.1 without reflection;
3. delivers `onSubscriptionStarted` before EVENT or EOSE for the corresponding sent REQ;
4. delivers EVENT and EOSE callbacks in wire order for one relay connection;
5. resends an active REQ and calls `onSubscriptionStarted` after reconnect;
6. accepts post-EOSE EVENT callbacks for a streaming REQ;
7. emits `ObservableEventStore.changes` only after persistent insert completion;
8. provides the documented EventStore query, duplicate, replacement, deletion, expiration, vanish, tag, and FTS behavior.

If any item fails, implementation stops and this specification must be revised. Production code must not compensate with reflection, Quartz internals, raw SQL, or copied Quartz/Amethyst source.

## 3. Architectural invariants

### 3.1 Local-first results

For `QuerySource.Local` and `QuerySource.LocalAndRemote`:

```text
relay EVENT
    -> decode
    -> verify NIP-01 ID and signature
    -> ordered insertion into Quartz EventStore
    -> successful commit or classified existing-record outcome
    -> local query/projection
    -> QueryState
```

These two modes never expose relay callback objects as result data.

### 3.2 Observe before request

Every local-and-remote query installs its store observer before starting its remote REQ. This prevents a fast relay response from committing between the initial local read and observation setup.

### 3.3 Data and synchronization state are independent

A network failure, timeout, reconnect, or EOSE transition must not clear successfully loaded data. Query errors are represented alongside the latest valid result list.

### 3.4 Remote-only exception

`QuerySource.Remote` is the sole exception to the local-first result path:

- it emits validated, non-expired events received by its relay subscription directly;
- it does not emit a pre-existing local seed;
- persistent incoming events are also enqueued for insertion into the Quartz event store;
- direct emission does not wait for the insert to commit;
- a persistence failure is surfaced in query state but cannot retract an event already emitted;
- EOSE still waits for preceding persistent events to reach a terminal insertion outcome, so one-shot completion does not abandon accepted saves.

Remote-only result membership and arrival order are session-scoped and are not persisted.

### 3.5 Ephemeral events

Kinds `20000..29999` have no persistent local projection:

- `Local` and `LocalAndRemote` do not return them;
- `Remote` may emit them directly after validation when they are not already expired;
- ephemeral events are never inserted into the canonical event store.

## 4. Public API

The declarations below are normative. Implementation imports the corresponding public types from the pinned Quartz artifact and `kotlinx.coroutines`.

### 4.1 Façade

```kotlin
class PurpleQuartz private constructor(...) : AutoCloseable {
    companion object {
        fun create(
            context: Context,
            websocketBuilder: WebsocketBuilder,
            parentScope: CoroutineScope,
            config: PurpleQuartzConfig = PurpleQuartzConfig(),
        ): PurpleQuartz
    }

    fun query(
        filter: Filter,
        source: QuerySource = QuerySource.Local,
    ): Flow<QueryState>

    fun query(
        filters: List<Filter>,
        source: QuerySource = QuerySource.Local,
    ): Flow<QueryState>

    override fun close()
}
```

Rules:

- `query` is the primary v1 API.
- The returned `Flow` is cold. Each collection owns one local observer and, when applicable, one remote request.
- Collection cancellation closes that request and removes all listeners owned by the collection.
- For `LocalAndRemote`, the local observer is active and the first local seed is sent to the collector before the remote request starts.
- Multiple filters produce one logical query state.
- The façade does not expose `NostrClient`, `subscribeAsFlow`, relay event callbacks, the SQLite connection pool, or raw SQL.
- `create` stores `context.applicationContext`, resolves `databaseName` with `Context.getDatabasePath`, creates `EventStore(dbName = absolutePath, relay = null)` with Quartz's default indexing strategy and published fixed reader count, wraps it in exactly one `ObservableEventStore`, and creates exactly one `NostrClient`.
- Because the canonical local store has `relay = null`, Quartz applies only NIP-62 requests tagged to vanish from everywhere. Relay-specific vanish requests apply to relay-scoped caches and intentionally do not cascade in this unscoped aggregate store.
- `parentScope` and `websocketBuilder` are caller-owned and are never cancelled or closed by `PurpleQuartz`; the façade owns and cancels a `SupervisorJob` child of `parentScope`.
- Production construction passes the child scope to `NostrClient`. Internal test seams may inject `INostrClient`, `IEventStore`, a clock, and ID generator, but these are not public API.
- Invalid construction configuration throws `IllegalArgumentException`. A second live façade for the same canonical database path throws `IllegalStateException`.
- Calling `query` after `close` returns a flow that emits one `Failed` state with `QueryError.Lifecycle` and then completes.
- Local observers requery after every `ObservableEventStore.changes` notification, not only changes whose inserted event matches the filter, because deletion, replacement, expiration, and vanish side effects may remove other matching rows. Equal consecutive item lists are not re-emitted unless synchronization or error state changed.
- Observer startup uses an explicit handshake and startup gate: change notifications may queue immediately, but no change-triggered projection may emit before the initial seed. After the seed is sent, one pending requery closes the observe/read race before normal observation continues.
- Each collection serializes item, synchronization, relay, and error updates through one coordinator. Remote item states are not conflated: every validated first-seen event causes a state emission before its persistence wait.
- Collection startup atomically registers with the façade or observes the closed state; it cannot subscribe after façade shutdown has begun.

### 4.2 Source selection

```kotlin
sealed interface QuerySource {
    data object Local : QuerySource

    data class LocalAndRemote(
        val relays: Set<NormalizedRelayUrl>,
        val mode: RemoteMode = RemoteMode.Stream,
    ) : QuerySource {
        init { require(relays.isNotEmpty()) }
    }

    data class Remote(
        val relays: Set<NormalizedRelayUrl>,
        val mode: RemoteMode = RemoteMode.Stream,
    ) : QuerySource {
        init { require(relays.isNotEmpty()) }
    }
}

sealed interface RemoteMode {
    data class OneShot(
        val timeout: Duration? = null,
    ) : RemoteMode {
        init { require(timeout == null || (timeout.isFinite() && timeout.isPositive())) }
    }

    data object Stream : RemoteMode
}
```

`LocalAndRemote` and `Remote` reject an empty relay set. `OneShot.timeout == null` uses `PurpleQuartzConfig.oneShotTimeout`; an explicit timeout must be finite and greater than zero.

At collection start, the implementation snapshots the relay set and deep-copies each filter's lists and tag maps. That immutable snapshot is used for both store queries and the Quartz subscription, so caller mutation after collection starts cannot change an active query.

#### `QuerySource.Local`

- Opens no relay connection and sends no REQ.
- Emits the current local result set.
- Continues observing matching committed store changes until collection ends.
- Reports `QuerySync.LocalOnly`.

#### `QuerySource.LocalAndRemote`

- Emits the current local result set without waiting for the network.
- Starts the remote REQ after local observation is installed.
- Re-runs the local projection after every observable store change and emits only changed results.
- Includes all locally stored events matching the filters, regardless of which request inserted them.
- Retains cached results through network errors and reconnects.

#### `QuerySource.Remote`

- Does not emit the pre-existing local result set as query data.
- Emits validated events directly from that query's relay subscription.
- Saves persistent incoming events to the local store without delaying direct emission.
- Preserves relay arrival order within the query.
- Does not promise durable result provenance.

### 4.3 Relay selection

Rules:

- The relay set must not be empty.
- One Quartz subscription ID is created per collected query. The same filter list is mapped to every relay in the set and passed once to `INostrClient.subscribe`.
- The request is sent only to the specified relays. The wrapper must not add discovery, indexer, search, fallback, hinted, NIP-65, or previously connected relays.
- Search filters may target one or more explicit relays; result ordering across relays is callback arrival order.
- Remote `Filter.limit` remains relay-scoped. The wrapper does not impose an additional aggregate limit across relays.
- NIP-65/outbox routing is out of scope for v1 because Quartz 1.12.6 does not expose Amethyst's application-level routing policy as a generic query operation.

### 4.4 Query state

```kotlin
data class QueryState(
    val items: List<Event>,
    val sync: QuerySync,
    val relays: Map<NormalizedRelayUrl, RelayQueryState> = emptyMap(),
    val error: QueryError? = null,
)

sealed interface QuerySync {
    data object LocalOnly : QuerySync
    data object Connecting : QuerySync
    data object CatchingUp : QuerySync
    data object Live : QuerySync
    data object Complete : QuerySync
    data object TimedOut : QuerySync
    data object Failed : QuerySync
}

data class RelayQueryState(
    val generation: Long,
    val connection: RelayConnectionState,
    val eose: Boolean,
    val lastError: String? = null,
)

enum class RelayConnectionState {
    Connecting,
    Connected,
    Disconnected,
    Closed,
}

sealed interface QueryError {
    val message: String

    data class InvalidQuery(override val message: String) : QueryError
    data class UnsupportedLocalProjection(override val message: String) : QueryError
    data class RelayFailure(
        val relay: NormalizedRelayUrl,
        override val message: String,
    ) : QueryError
    data class VerificationRejected(
        val relay: NormalizedRelayUrl,
        val eventId: String?,
        override val message: String,
    ) : QueryError
    data class ProtocolViolation(
        val relay: NormalizedRelayUrl,
        val eventId: String?,
        override val message: String,
    ) : QueryError
    data class PersistenceFailure(
        val eventId: String?,
        override val message: String,
    ) : QueryError
    data class IngestionSaturated(
        val relay: NormalizedRelayUrl,
        override val message: String,
    ) : QueryError
    data class Timeout(
        val duration: Duration,
        override val message: String,
    ) : QueryError
    data class IncompatibleDependency(override val message: String) : QueryError
    data class Lifecycle(override val message: String) : QueryError
}
```

State rules:

- For `Local` and `LocalAndRemote`, `items` comes from an event-store query or projection.
- For `Remote`, `items` contains validated events emitted by that query's relay subscription.
- `Connecting` means at least one requested relay has no active sent REQ for its current connection.
- `CatchingUp` means every requested relay has an active generation and at least one has not reached EOSE.
- `Live` means every currently routed relay reached EOSE and a streaming query remains subscribed.
- `Complete` is terminal success for a one-shot remote query.
- `TimedOut` and `Failed` stop the remote request but do not erase `items`.
- Every requested relay is present in `relays` from the first network state. Generation `1` is allocated immediately before the initial `subscribe` call.
- The first `onSubscriptionStarted` for that generation sets `connection = Connected`, resets `eose = false`, and clears transient `lastError`.
- After a generation has started, a reconnect's `onConnecting` increments that relay's generation, sets `connection = Connecting`, and resets `eose`; `onSubscriptionStarted` then marks the replacement REQ active. Initial connection attempts before generation 1 starts do not increment it.
- EOSE marks only the generation current when its callback entered the ordered ingestion boundary.
- A source with zero matching events must still progress out of its initial loading/catching-up state.
- `Local` emits exactly one initial `LocalOnly` state even when empty, then emits only when a relevant store change produces a different item list.
- `LocalAndRemote` first emits its local seed with `Connecting`, starts the REQ, and then updates synchronization independently of data.
- `Remote` first emits an empty `Connecting` state and never reads the store to seed `items`.
- A one-shot flow emits exactly one terminal `Complete`, `TimedOut`, or `Failed` state and then completes. Before `LocalAndRemote` emits `Complete`, it performs a final local query so all preceding successful commits are represented.
- `LocalAndRemote(Stream)` remains collected after `Live`; `LocalAndRemote(OneShot)` stops local observation after its terminal state.
- Local result ordering and per-filter limits are exactly the ordering and union semantics returned by Quartz `EventStore.query(filters)`.
- Remote result order is the order in which validated, first-seen event IDs leave the query's ingestion worker. Duplicate IDs from the same or different relays are ignored after the first valid occurrence.

For a network-backed nonterminal query, `sync` is derived in this priority order:

1. if any requested relay is not `Connected`, `Connecting`;
2. otherwise, if any requested relay has `eose == false`, `CatchingUp`;
3. otherwise, `Live` for `Stream`;
4. otherwise, after all per-relay ingestion barriers and any final local projection, `Complete` for `OneShot`.

Error rules:

- Empty filter lists produce one terminal `Failed` state with `InvalidQuery`.
- A local store query failure produces terminal `Failed` with `UnsupportedLocalProjection`.
- Invalid relay events are dropped, emit `VerificationRejected`, and do not stop an otherwise healthy stream. The error clears on the next accepted EVENT or EOSE; relay-specific diagnostics remain in `RelayQueryState.lastError`.
- A valid event that does not match any snapshotted filter under Quartz `Filter.match` is dropped as `ProtocolViolation` and is neither emitted nor persisted. `search` remains relay-evaluated because `Filter.match` intentionally does not evaluate NIP-50 text relevance.
- A connection failure is nonterminal for a stream because Quartz retains demand and retries. It sets `RelayFailure`, preserves `items`, and reports `Connecting`.
- A relay `CLOSED` response, persistence failure, ingestion saturation, or incompatible runtime behavior is terminal `Failed` and unsubscribes the whole logical query.
- For one-shot mode, connection attempts continue only until the absolute timeout. Timeout starts immediately after the local seed for `LocalAndRemote`, or immediately after the initial empty state for `Remote`; reconnects do not reset it.
- On timeout, the collection stops accepting callbacks and unsubscribes immediately, drains every EVENT already accepted by its FIFO to a terminal verification/persistence outcome, emits `TimedOut`, and completes. The timeout is an absolute network intake deadline; a blocked store operation may delay the terminal state beyond that deadline.
- When several nonterminal errors occur, `error` contains the most recent one. `relays[relay].lastError` preserves each relay's latest sanitized connection diagnostic.
- Messages are stable, sanitized summaries. Event content, tag values, search text, private keys, authorization payloads, full filters, raw relay frames, and exception stack traces must not appear in public errors or production logs. `eventId` may contain only a validated 64-character lowercase hexadecimal ID; otherwise it is `null`.
- Validation catches malformed-encoding exceptions and maps them to `VerificationRejected`. Coroutine `CancellationException` is always rethrown and is never converted into a query error.

## 5. Configuration and ownership

```kotlin
data class PurpleQuartzConfig(
    val databaseName: String = "purplequartz.db",
    val oneShotTimeout: Duration = 30.seconds,
    val ingestionCapacity: Int = 1_024,
    val expirationSweepInterval: Duration = 1.minutes,
)
```

Validation:

- `databaseName` is nonblank, contains no `/`, `\`, or NUL, and resolves through `applicationContext.getDatabasePath(databaseName)`;
- `oneShotTimeout` is finite and greater than zero;
- `ingestionCapacity >= 1`;
- `expirationSweepInterval` is finite and greater than zero.
- `parentScope` is active when `create` is called.

`PurpleQuartz` owns:

- one child coroutine scope;
- one Quartz client;
- one SQLite event store;
- one periodic expiration-sweep job;
- request and ingestion coordinators.

Each remotely backed collection owns one bounded FIFO channel of `ingestionCapacity`, one ingestion worker, one Quartz subscription ID, one subscription listener, and one connection listener. Store access may run on the façade child scope, but collection cancellation remains linked to the collecting coroutine.

An internal lifecycle gate rejects new event-store operations after shutdown starts and counts in-flight operations without serializing Quartz's concurrent readers. Closing atomically marks the façade closed, stops callback acceptance, unsubscribes active subscriptions, removes listeners, closes the Quartz client, cancels the child job, waits for the in-flight store-operation count to reach zero, closes the event store, and finally removes the canonical database path from the process ownership registry. Concurrent or repeated `close` calls return only after the first close sequence finishes. The façade does not close the caller's `WebsocketBuilder`, `OkHttpClient`, or `parentScope`.

Every admitted store operation decrements the in-flight count from a `NonCancellable` `finally` block. Cancellation cannot strand the count above zero or make `close` wait forever.

The event store must use Quartz's supported bundled SQLite driver and configuration. Production code must not access Quartz's connection pool, mutate Quartz's schema, or maintain a second canonical event store.

While open, the façade calls `ObservableEventStore.deleteExpiredEvents()` once per `expirationSweepInterval`. The first sweep occurs after one full interval. Query-list distinctness suppresses no-op sweep emissions.

## 6. Remote ingestion and EOSE

Relay subscription-start, EVENT, EOSE, CLOSED, and connection-failure callbacks for one request, relay, and connection generation enter the same ordered ingestion boundary.

Callbacks are converted to immutable internal messages. The listener uses non-suspending `trySend`; it never blocks Quartz callback threads. A failed `trySend` because the channel is full atomically marks the collection failed with `IngestionSaturated`, unsubscribes the whole collected subscription, and rejects later callbacks. A failed send because the collection is already cancelled or closed is ignored as stale cleanup, not reported as saturation.

Required ordering:

```text
EVENT A -> EVENT B -> EOSE
```

EOSE for that generation is not visible until A and B each reach a terminal ingestion outcome:

- committed;
- valid duplicate already present;
- expected canonical no-op, such as a losing replaceable event;
- validated and directly emitted ephemeral event in `Remote` mode;
- typed verification or persistence failure.

An unexpected persistence failure fails the affected query. It must not be converted into successful EOSE.

Streaming queries continue ingesting events after EOSE. A reconnect starts a new generation, resets EOSE, and resends the active filters.

The ingestion boundary is bounded. Saturation must fail and close the whole collected subscription; EVENT and EOSE messages must never be silently dropped.

Verification and persistence are serialized by the collection's ingestion worker:

- validate with `verifyId()` and `verifySignature()` before any result emission or store call;
- require the validated event to match at least one snapshotted filter under `Filter.match`;
- treat an already-expired event as an expected canonical no-op: do not emit or persist it;
- never call `Event.verify()` or `checkSignature()` because their logging/error strings are outside this library's sanitization contract;
- for local-first modes, await insertion before processing the next queued message;
- for remote mode, update and emit direct items before awaiting persistent insertion;
- do not insert kinds `20000..29999`;
- after an insert throws, query the store by event ID: an exact existing ID is a terminal duplicate outcome; an event that became expired is an expected canonical no-op; otherwise fail with `PersistenceFailure`;
- a successful `insert` is terminal even when Quartz canonicalization makes the event a replaceable/addressable no-op;
- process EOSE only after every earlier queued event has reached one of these terminal outcomes.

## 7. Local event-store behavior

Quartz `EventStore` is the sole v1 persistent source of truth.

`purplequartz` relies on Quartz for:

- unique event IDs;
- replaceable and addressable winner selection;
- timestamp and lexical-ID tie-breaking;
- owned NIP-09 deletion and anti-resurrection;
- NIP-40 expiration;
- NIP-50 local FTS;
- NIP-62 vanish;
- one-letter tag indexes;
- migrations, WAL, and concurrent readers.

The wrapper must:

- verify remote events before direct emission or insertion;
- reject ephemeral events before `ObservableEventStore.insert`;
- use explicit insertion error handling rather than Quartz helpers that swallow failures;
- expose only post-commit persistent changes in local-first modes;
- document inherited projection limitations;
- open and close the store exactly once per façade instance.

Two live `PurpleQuartz` instances in one process must not open the same canonical database path. The factory enforces this with a process-wide synchronized registry. A failed construction releases a reserved path; `close` releases it exactly once.

## 8. Relay selection behavior

Version 1 supports exact caller-specified relay sets only. Relay URLs are already normalized Quartz values, sets are deduplicated by value, and the wrapper maps all query filters to each relay without mutation.

Amethyst's outbox implementation combines application caches, relay hints, defaults, failure tracking, and NIP-65 events. Quartz 1.12.6 supplies the transport and NIP-65 protocol types but no equivalent generic query-router operation. Outbox routing is therefore explicitly deferred instead of being approximated.

## 9. Version 1 requirements

### R1. Android library foundation

- **Current:** only this specification and research reference trees exist.
- **Target:** a consumable Android library module named `purplequartz`, targeting API 29+, using the pinned toolchain and published Quartz artifact.
- **Acceptance:** an API-29 sample app compiles and runs; removing `reference/` does not affect dependency resolution, compilation, or tests.

### R2. Unified reactive query

- **Current:** no wrapper API exists.
- **Target:** `query` combines local observation and optional remote synchronization according to `QuerySource`; consumers never coordinate subscription IDs, listeners, or projections.
- **Acceptance:** tests collect every source mode through only the public API and observe the state transitions in section 4.

### R3. Local-first enforcement

- **Current:** no enforcement boundary exists.
- **Target:** every event emitted by `Local` or `LocalAndRemote` is loaded from the event store; `Remote` is the sole direct-emission exception.
- **Acceptance:** with insertion blocked, a received event is absent from local-first states until commit and appears afterward; failed insertion never exposes it through those modes.

### R4. Source semantics

- **Current:** no source modes exist.
- **Target:** all three modes implement the seed, membership, ordering, completion, and network behavior in section 4.
- **Acceptance:** the source-mode verification cases in section 10 pass, including no local seed for `Remote` and background persistence of its persistent events.

### R5. Validation and persistence visibility

- **Current:** relay events have no wrapper-owned validation gate.
- **Target:** ID/signature verification and filter-membership checking precede direct emission and insertion, and unexpected storage failures remain visible.
- **Acceptance:** mutations to pubkey, tags, content, kind, timestamp, ID, or signature are rejected; valid off-filter events are dropped as `ProtocolViolation`; storage faults produce `PersistenceFailure` without violating source visibility rules.

### R6. EOSE barrier and streaming

- **Current:** no ordered ingestion or EOSE barrier exists.
- **Target:** EOSE waits for all preceding events in its relay/request generation; streams continue after EOSE; one-shot closes after all requested relays reach EOSE or timeout.
- **Acceptance:** controlled callback tests prove per-generation ordering, multi-relay completion, post-EOSE streaming, reconnect reset, and absolute timeout.

### R7. Relay selection

- **Current:** no relay policy exists and Quartz exposes no generic Amethyst outbox router.
- **Target:** remote queries use exactly their nonempty caller-specified relay set; no other relay-selection policy exists in v1.
- **Acceptance:** recorded `subscribe` arguments contain exactly the requested relays and value-equivalent filter snapshots; source and bytecode contain no wrapper-owned outbox, hint, discovery, recommendation, or fallback implementation.

### R8. Reconnection and lifecycle

- **Current:** no façade lifecycle exists.
- **Target:** Quartz owns reconnect timing while query demand exists; collection cancellation and façade closure release only their owned resources.
- **Acceptance:** reconnect increments generation and resends filters; cancellation/closure tests report zero remaining subscriptions, listeners, ingestion jobs, and open store handles.

### R9. Bounded ingestion

- **Current:** no ingestion boundary exists.
- **Target:** every remotely backed collection has the bounded, fail-closed FIFO described in section 6.
- **Acceptance:** capacity tests force overflow, observe `IngestionSaturated` and unsubscribe, and never observe `Live`/`Complete` from a dropped EOSE.

### R10. Dependency compatibility

- **Current:** the published coordinate and public APIs have been identified, but no project compatibility suite exists.
- **Target:** compatibility tests pin and exercise every behavior in section 2.2 against `com.vitorpamplona.quartz:quartz:1.12.6`.
- **Acceptance:** the gate passes on JVM host tests where supported and on an API-29 Android target; dependency locking/verification confirms 1.12.6; no production reflection, raw SQL, or Quartz internal field access exists.

## 10. Verification matrix

### Local-first

- [ ] `Local` emits cached events with all relays offline.
- [ ] `LocalAndRemote` installs observation and emits its local seed before opening REQs.
- [ ] `Remote` suppresses the pre-existing local seed.
- [ ] A newly received event is absent from `Local` and `LocalAndRemote` while insertion is blocked.
- [ ] `LocalAndRemote` emits a newly received event only after successful commit.
- [ ] A failed insertion never emits the event through a local-first mode.
- [ ] `Remote` emits a validated incoming event without waiting for local insertion.
- [ ] `Remote` also saves persistent incoming events.
- [ ] A remote persistence failure is surfaced without retracting an already emitted event.
- [ ] Ephemeral events are available only through `Remote` and are never persisted.
- [ ] Already-expired relay events are neither emitted nor persisted and do not block EOSE.
- [ ] A store mutation occurring after observer installation but during the seed query is represented in a subsequent local-first state.
- [ ] Local-first item order is exactly the order returned by `EventStore.query(filters)`.
- [ ] Remote duplicate IDs from one or several relays appear once at their first validated arrival position.

### Store semantics

- [ ] Duplicate IDs are idempotent.
- [ ] Replaceable/addressable winner selection updates active queries.
- [ ] Owned deletion, expiration, and vanish-from-everywhere update active queries; relay-specific vanish requests do not cascade in the unscoped local store.
- [ ] Periodic expiration sweeps remove expired rows from active queries without emitting unchanged item lists for no-op sweeps.
- [ ] Invalid IDs and signatures never persist.
- [ ] Valid off-filter relay events are neither emitted nor persisted.
- [ ] Local full-text search survives database reopen.
- [ ] Tag filters query committed events.
- [ ] An insertion exception followed by an exact stored-ID lookup is classified as a duplicate only when that ID exists.
- [ ] A successful canonical no-op unblocks a following EOSE without exposing a noncanonical event.
- [ ] An event that expires between direct remote emission and insertion is classified as an expected persistence no-op without retracting it.

### Query lifecycle

- [ ] Empty local results do not prevent a remote REQ.
- [ ] Empty relay responses reach EOSE.
- [ ] EOSE waits for every preceding insertion.
- [ ] One relay's EOSE does not complete a multi-relay query.
- [ ] Post-EOSE events persist and render for streams.
- [ ] One-shot queries unsubscribe after all-relay EOSE.
- [ ] One-shot timeout is absolute across relay selection and reconnect.
- [ ] Cancelling collection closes its request and rejects stale callbacks.
- [ ] Repeated collection and cancellation leak no jobs or subscriptions.
- [ ] Every requested relay starts at generation 1 immediately before subscribe; each reconnect increments only that relay before its replacement REQ.
- [ ] Disconnect/reconnect resets EOSE and moves a stream from `Live` through `Connecting`/`CatchingUp` back to `Live`.
- [ ] `LocalAndRemote(OneShot)` performs a final store query before its terminal `Complete`.
- [ ] Empty filter lists emit one `InvalidQuery` failure and do not subscribe.
- [ ] Collection after façade closure emits one `Lifecycle` failure and completes.
- [ ] Mutating caller-owned relay/filter collections after collection starts does not alter the active local query or REQ.

### Relay selection

- [ ] REQs go only to the specified nonempty relay set.
- [ ] Every requested relay receives a value-equivalent immutable snapshot of the logical filter list.
- [ ] No NIP-65, outbox, hint, discovery, recommendation, indexer, search-relay, or fallback routing algorithm exists in `purplequartz`.

### Failure and shutdown

- [ ] Ingestion saturation cannot silently drop EVENT or EOSE.
- [ ] Saturation cannot produce a false `Live` state.
- [ ] Network errors preserve the latest valid `items`.
- [ ] A stream retains relay demand after a connect failure and recovers through Quartz reconnect behavior.
- [ ] Relay `CLOSED`, persistence failure, and saturation each emit one terminal `Failed` state and unsubscribe.
- [ ] Verification rejection is visible but does not stop a healthy stream.
- [ ] Protocol violations are visible but do not stop a healthy stream.
- [ ] Library closure releases sockets, listeners, subscriptions, store connections, and child jobs.
- [ ] Closing twice is harmless and releases the database ownership reservation once.
- [ ] A second live façade for the same canonical database path is rejected; a new façade can open it after close.
- [ ] Event content, tag values, search text, full filters, raw frames, authorization payloads, private keys, and stack traces are absent from public errors and production logs.
- [ ] Tests run against the pinned published Quartz artifact.
- [ ] An API-29 consumer constructs the façade with `BasicOkHttpWebSocket.Builder`, queries all three source modes, and closes it.

## 11. Version 1 boundaries

### In scope

- Android API 29+ library module named `purplequartz`.
- Quartz protocol and event-store integration.
- Reactive `query`.
- Local, local-and-remote, and remote-only source selection.
- Store-only result emission for local and local-and-remote modes.
- Direct validated relay results for remote-only mode.
- NIP-01 validation before direct emission or persistence.
- One-shot and streaming REQs.
- EOSE ordering and reconnect generation tracking.
- Exact caller-specified relay sets.
- Local NIP-50 FTS through Quartz filters.
- Ordinary Nostr tag queries.
- Deterministic compatibility, lifecycle, and invariant tests.

### Out of scope

- Publishing and NIP-20 delivery tracking.
- Signing convenience APIs and key persistence.
- Durable publish retry.
- Relay provenance persisted across process death.
- Exact relay-ranked NIP-50 result persistence.
- NIP-65/outbox relay discovery or routing; Quartz 1.12.6 has no generic Amethyst-equivalent query router.
- Locally projected or persisted ephemeral events.
- Relationship models, foreign keys, cascades, and nested query managers.
- A wrapper-owned companion database.
- Zapstore-specific event kinds, repositories, UI, or navigation.
- Compose UI components.
- A mandatory DI framework.
- Kotlin Multiplatform publication.
- A custom WebSocket stack or parallel relay pool.
- Modifying Quartz database internals.

## 12. Delivery artifacts

Expected implementation artifacts:

- `settings.gradle.kts`;
- root Gradle build and version catalog;
- `purplequartz/build.gradle.kts`;
- `purplequartz/src/main/kotlin/dev/zapstore/purplequartz/PurpleQuartz.kt`;
- `purplequartz/src/main/kotlin/dev/zapstore/purplequartz/PurpleQuartzConfig.kt`;
- public query source and state types;
- internal request and ingestion coordinators;
- unit and integration tests under `purplequartz/src/test`;
- Android store/lifecycle tests under `purplequartz/src/androidTest`;
- root `README.md` with source-mode and lifecycle examples;
- compatibility notes for the pinned Quartz version.

## 13. Release criteria

Version 1 is complete only when:

1. requirements R1–R10 are implemented;
2. every verification checkbox passes;
3. only `QuerySource.Remote` exposes a relay-to-consumer event path;
4. Android tests and lint pass;
5. compatibility tests pass against the pinned published Quartz artifact;
6. a sample API-29 consumer demonstrates all three source modes;
7. removing `reference/amethyst` does not break dependency resolution or compilation.

## 14. Decision log

- Name: `purplequartz`.
- Version 1 priority: enforce the local-first invariant.
- Foundation: a small Quartz wrapper, not a second Nostr stack.
- Public API: reactive `query` returning Quartz `Event` values.
- Source choices: local, local-and-remote, and remote-only.
- Default source: local; every network query requires an explicit nonempty relay set.
- Remote-only meaning: validated relay events emit directly and persistent events are also saved.
- Database: Quartz event store is the sole canonical persistent source.
- Relays: exact caller-specified sets only.
- NIP-65/outbox routing: deferred because it is Amethyst application policy, not a generic Quartz 1.12.6 query capability.
- Dependency: published `com.vitorpamplona.quartz:quartz:1.12.6`; `reference/` remains research-only.
- Construction: application context, caller-owned Quartz `WebsocketBuilder`, caller-owned parent scope, validated config.
- Publishing, signing helpers, companion storage, and relationship models: deferred beyond v1.

## 15. Edge coverage and readiness

Resolved implementation edges:

- observe/read race: observer handshake, startup gate, and pending requery;
- zero results: initial states and EOSE transitions are independent of item count;
- duplicate delivery: first validated remote occurrence wins; store exceptions are rechecked by exact ID;
- slow or failed storage: local-first visibility waits for commit; remote direct visibility does not;
- timeout with accepted work: intake stops at the deadline and the accepted FIFO drains before `TimedOut`;
- reconnect after EOSE: per-relay generation increments and EOSE resets;
- multi-relay EOSE: all current generations must cross their barriers;
- mutable caller inputs: relay and filter values are snapshotted at collection start;
- cancellation/close races: active registration and callback acceptance close atomically;
- repeated façade creation: canonical database-path ownership is reserved and released;
- expiration without new relay traffic: periodic observable sweeps trigger projections;
- malicious relay behavior: invalid and valid-but-off-filter events are dropped before persistence or emission.

Explicit prohibitions:

- no relay EVENT-to-consumer path exists for `Local` or `LocalAndRemote`;
- no ephemeral event reaches the canonical store;
- no hidden outbox, NIP-65 discovery, recommendation, or fallback policy is added;
- no reflection, raw SQL, Quartz internal-field access, copied reference source, or second canonical database is used;
- no caller-owned scope, WebSocket builder, or HTTP client is closed;
- no sensitive event/filter/frame material is placed in public errors or production logs.

Ambiguity assessment:

```text
Goal clarity:        0.98
Boundary clarity:    0.96
Constraint clarity:  0.93
Acceptance criteria: 0.95
Weighted ambiguity:  0.04
Implementation gate: PASS (required <= 0.20)
```
