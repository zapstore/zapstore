# purplequartz

`purplequartz` is an Android API 29+ query wrapper for Quartz 1.12.6. Local and local-and-remote queries expose events read from Quartz's persistent event store. Remote-only queries expose verified relay events directly and save persistent events in the background. Local-and-remote queries can use a persistent max-age policy to avoid unnecessary relay requests.

## Setup

The repository contains the Android library module `:purplequartz` and the Zapstore API-29 app in `:app`. The build uses JDK 21, Gradle 9.4.1, Android Gradle Plugin 9.2.1, and Kotlin 2.4.0.

```kotlin
dependencies {
    implementation(project(":purplequartz"))
}
```

Create one facade for each database:

```kotlin
val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
val httpClient = OkHttpClient()

val purpleQuartz = PurpleQuartz.create(
    context = applicationContext,
    websocketBuilder = BasicOkHttpWebSocket.Builder { httpClient },
    parentScope = parentScope,
)
```

`PurpleQuartz` owns its child scope, Quartz client, and event store. The caller retains ownership of `parentScope`, `httpClient`, and the WebSocket builder.

## Query sources

Local queries emit the current store projection and keep observing store changes:

```kotlin
purpleQuartz.query(
    filter = Filter(kinds = listOf(1)),
).collect { state ->
    render(state.items)
}
```

Local-and-remote queries install the store observer, emit the local seed, then open the relay request. Relay events become visible after verification and store commit.

```kotlin
val relays = setOf("wss://relay.example".normalizeRelayUrl())

purpleQuartz.query(
    filter = Filter(kinds = listOf(1)),
    source = QuerySource.LocalAndRemote(
        relays = relays,
        mode = RemoteMode.Stream,
    ),
).collect(::renderState)
```

Remote-only queries emit verified events in callback arrival order. Persistent events are also written to the local store.

```kotlin
purpleQuartz.query(
    filter = Filter(kinds = listOf(1), limit = 50),
    source = QuerySource.Remote(
        relays = relays,
        mode = RemoteMode.OneShot(timeout = 15.seconds),
    ),
).collect(::renderState)
```

Every network query requires an explicit, nonempty relay set. The library does not add outbox, fallback, discovery, or hinted relays.

### Cached local-and-remote queries

Set `maxAge` when data may be served from the local store without immediately opening a relay request. For example, this profile query refreshes at most once every six hours:

```kotlin
purpleQuartz.query(
    filter = Filter(
        authors = listOf(profilePubkey),
        kinds = listOf(0),
        limit = 1,
    ),
    source = QuerySource.LocalAndRemote(
        relays = relays,
        mode = RemoteMode.OneShot(),
        maxAge = 6.hours,
    ),
).collect(::renderState)
```

Freshness is recorded only after every requested relay reaches EOSE and preceding events are committed. Empty successful responses are cached too. Timeouts, failures, partial responses, cancellation, and local-only changes do not refresh the timestamp.

The cache key contains the complete filter set and exact normalized relay set; `maxAge` and remote mode are policy and are not part of the key. Only a SHA-256 fingerprint and refresh timestamp are persisted, not filter contents. Freshness survives process restarts and is reset when the Quartz database file is deleted or replaced. Out-of-band in-place mutation of the owned Quartz database is unsupported.

- Fresh `OneShot` queries emit the local projection as `Complete` and open no request.
- Fresh `Stream` queries report `Cached`, keep observing local commits, and defer their relay subscription until the max-age window expires. A successful concurrent refresh extends that delay.
- `Remote` always opens its requested subscription and bypasses this local cache policy.
- Omitting `maxAge` preserves the existing always-refresh behavior.

## State and failure handling

`QueryState.items` survives connection failures and synchronization transitions. Inspect `sync` for query progress and `error` for the latest failure. PurpleQuartz observes Android's default network callback and requests an immediate retry when a network becomes available, without waiting for relay backoff. Hosts should also call `purpleQuartz.refreshConnections()` when their app returns to the foreground.

- `LocalOnly`: the query reads and observes the local store.
- `Cached`: a local-and-remote stream is observing the fresh local projection and has deferred its relay request.
- `Connecting`: at least one relay has no active request.
- `CatchingUp`: every relay is connected and at least one is waiting for EOSE.
- `Live`: all relays reached EOSE and a streaming request remains active.
- `Complete`, `TimedOut`, and `Failed`: terminal states for one-shot or failed requests.

Invalid relay events and off-filter events are dropped before emission or persistence. Persistent local-first results appear only after commit. Ephemeral kinds `20000..29999` are available through `QuerySource.Remote` and never enter the store.

## Lifecycle

Canceling a collector removes its subscription and listeners. Close the facade when the application component that owns it shuts down:

```kotlin
purpleQuartz.close()
parentScope.cancel()
httpClient.dispatcher.executorService.shutdown()
httpClient.connectionPool.evictAll()
```

`close()` waits for facade-owned jobs and store operations to finish. Call it from a worker thread when shutdown could coincide with slow storage. Active collectors receive `QueryError.Lifecycle` and complete. Calls made after closure emit the same failure and complete.

Only one live `PurpleQuartz` instance may own a canonical database path in a process. A new instance can open the path after the current owner closes.

## Verification

Run host tests and lint:

```shell
./gradlew :purplequartz:testDebugUnitTest :purplequartz:lintDebug
```

Compile the API-29 consumer and Android compatibility suite:

```shell
./gradlew :app:assembleDebug :purplequartz:assembleDebugAndroidTest
```

Run the device suite on an API-29+ emulator or device:

```shell
./gradlew :purplequartz:connectedDebugAndroidTest
```

The device suite exercises the published Quartz Android artifact, NIP-01 verification, post-commit observation, database reopen, local full-text search, and all three public query sources.

Module lock files pin both the Quartz metadata coordinate and its selected Android variant to 1.12.6. Regenerate them with `./gradlew :purplequartz:dependencies :app:dependencies --write-locks` after an intentional dependency update.

The `reference/` directory contains research checkouts. Gradle does not include them in dependency resolution, compilation, tests, or runtime resources.
