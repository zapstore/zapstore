use jni::{jni_sig, jni_str, EnvUnowned};
use jni::errors::{LogErrorAndDefault, Result as JniResult, ThrowRuntimeExAndDefault};
use jni::objects::{Global, JByteArray, JClass, JObject, JString};
use jni::sys::{jint, jlong};
use jni::JavaVM;

use arti_client::{BootstrapBehavior, DataReader, DataWriter, IsolationToken, StreamPrefs, TorClient};
use arti_client::config::TorClientConfigBuilder;
// `kind()` is a trait method (tor_error::HasKind), not inherent, so the trait has to be in
// scope wherever we classify a connect failure. Both are re-exported by arti-client.
use arti_client::HasKind;
use tor_rtcompat::PreferredRuntime;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::Notify;

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, Once, OnceLock};
use std::path::PathBuf;
use std::time::Duration;

// ============================================================================
// Global State
// ============================================================================

static ARTI_CLIENT: Mutex<Option<Arc<TorClient<PreferredRuntime>>>> = Mutex::new(None);
static TOKIO_RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);
static JAVA_VM: Mutex<Option<JavaVM>> = Mutex::new(None);
static LOG_CALLBACK: Mutex<Option<Global<JObject<'static>>>> = Mutex::new(None);
// The background directory download started by initialize(). It holds an Arc<TorClient>, so
// destroy() must abort it too — otherwise the client cannot drop, the state file lock is never
// released, and the next initialize() fails.
static BOOTSTRAP_TASK: Mutex<Option<tokio::task::JoinHandle<()>>> = Mutex::new(None);
// Open streams by the handle Kotlin holds. There is no listening socket: a stream exists only
// because this process asked for it through connect().
static STREAMS: Mutex<BTreeMap<jlong, Arc<TorStream>>> = Mutex::new(BTreeMap::new());
static NEXT_STREAM: AtomicI64 = AtomicI64::new(1);
// One token per Kotlin TorIsolation ordinal: anonymous and identity traffic never share a circuit.
static ISOLATION: OnceLock<[IsolationToken; 2]> = OnceLock::new();
static INIT_ONCE: Once = Once::new();

// Return codes shared with ArtiStreams in ArtiNative.kt.
const CONNECT_NO_CLIENT: jlong = -1;
const CONNECT_FAILED: jlong = -2;
const CONNECT_HOST_NOT_FOUND: jlong = -3;
const CONNECT_REFUSED: jlong = -4;
const CONNECT_EXIT_POLICY: jlong = -5;
const CONNECT_NETWORK_UNREACHABLE: jlong = -6;
const CONNECT_TIMEOUT: jlong = -7;
const CONNECT_BAD_TARGET: jlong = -8;

const IO_EOF: jint = -1;
const IO_TIMEOUT: jint = -2;
const IO_CLOSED: jint = -3;
const IO_FAILED: jint = -4;

// Upper bound on one read's buffer, so a large Java array cannot force a large native allocation.
const MAX_READ_BYTES: usize = 64 * 1024;

// ============================================================================
// Logging
// ============================================================================

fn send_log_to_java(message: String) {
    let vm_opt = JAVA_VM.lock().unwrap();
    let callback_opt = LOG_CALLBACK.lock().unwrap();

    if let (Some(vm), Some(callback)) = (vm_opt.as_ref(), callback_opt.as_ref()) {
        // jni 0.22 only hands out an `Env` inside a closure, borrowed from an
        // attachment pinned to the stack; it also pushes a local-reference frame
        // per call, so `jmessage` is released when the closure returns instead of
        // accumulating on this long-lived logging thread.
        let _ = vm.attach_current_thread(|env| -> JniResult<()> {
            if let Ok(jmessage) = env.new_string(&message) {
                let _ = env.call_method(
                    &**callback,
                    jni_str!("onLogLine"),
                    jni_sig!("(Ljava/lang/String;)V"),
                    &[(&jmessage).into()],
                );
            }
            Ok(())
        });
    }
}

macro_rules! log_info {
    ($($arg:tt)*) => {{
        let msg = format!($($arg)*);
        send_log_to_java(msg);
    }};
}

macro_rules! log_error {
    ($($arg:tt)*) => {{
        let msg = format!("ERROR: {}", format!($($arg)*));
        send_log_to_java(msg);
    }};
}

// ============================================================================
// JNI Functions — package: dev.zapstore.app.transport
// ============================================================================

#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_getVersion<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    // jni 0.22: the raw environment pointer is FFI-only (`EnvUnowned`); JNI calls
    // need the `Env` that `with_env` borrows for the closure. `resolve` maps an
    // `Err` to the policy — here a Java RuntimeException plus a null return —
    // rather than losing it.
    //
    // Only the `Err` half is live: the policy's panic half runs through
    // `catch_unwind`, which catches nothing under this crate's
    // `panic = "abort"` release profile, so a panic in here still takes the
    // process down exactly as it did before the migration.
    env.with_env(|env| -> JniResult<JString<'caller>> {
        if JAVA_VM.lock().unwrap().is_none() {
            if let Ok(vm) = env.get_java_vm() {
                *JAVA_VM.lock().unwrap() = Some(vm);
            }
        }

        let version = format!("Arti {} (custom build with rustls)", env!("CARGO_PKG_VERSION"));
        env.new_string(version)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_setLogCallback<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    callback: JObject<'caller>,
) {
    env.with_env(|env| -> JniResult<()> {
        if JAVA_VM.lock().unwrap().is_none() {
            if let Ok(vm) = env.get_java_vm() {
                *JAVA_VM.lock().unwrap() = Some(vm);
            }
        }

        if let Ok(global_ref) = env.new_global_ref(&callback) {
            *LOG_CALLBACK.lock().unwrap() = Some(global_ref);
            log_info!("Log callback registered");
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Initialize Arti runtime and bootstrap the TorClient.
/// The TorClient is created once and reused for the app's lifetime.
#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_initialize<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    data_dir: JString<'caller>,
) -> jint {
    // Everything JNI-owned is read inside this closure; the rest of the function
    // is pure Rust that blocks on Tokio, which must not hold an `Env`.
    //
    // `None` carries a failed read, because it is `Option::default()` and so is
    // also what the policy yields for an `Err`. Both end at the same `-1` the
    // old `Err` arm returned. Resolving to `jint` directly would have defaulted
    // to `0`, the value this API reports as success.
    //
    // The already-initialized check deliberately stays *outside* the closure,
    // against the whole `Option`: threading it through as a sentinel value
    // would leave that sentinel to be re-tested after the closure, and a
    // concurrent destroy() landing in between would let it through as the data
    // directory.
    let data_dir_str: Option<String> = env
        .with_env(|env| -> JniResult<Option<String>> {
            if JAVA_VM.lock().unwrap().is_none() {
                if let Ok(vm) = env.get_java_vm() {
                    *JAVA_VM.lock().unwrap() = Some(vm);
                }
            }

            Ok(match data_dir.try_to_string(env) {
                Ok(s) => Some(s),
                Err(e) => {
                    log_error!("Failed to convert data_dir: {:?}", e);
                    None
                }
            })
        })
        .resolve::<LogErrorAndDefault>();

    // Already initialized — skip
    if ARTI_CLIENT.lock().unwrap().is_some() {
        log_info!("Arti already initialized, reusing existing client");
        return 0;
    }

    let data_dir_str: String = match data_dir_str {
        Some(s) => s,
        None => return -1,
    };

    log_info!("Initializing Arti with data directory: {}", data_dir_str);

    INIT_ONCE.call_once(|| {
        // arti-v2.3.0's tor-rtcompat no longer installs a rustls CryptoProvider
        // implicitly — without this, TorClient::create_bootstrapped panics on
        // first TLS handshake. install_default() returns Err if a provider is
        // already installed, which is fine; we just want at-least-one.
        let _ = rustls::crypto::ring::default_provider().install_default();

        match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => {
                log_info!("Tokio runtime created successfully");
                *TOKIO_RUNTIME.lock().unwrap() = Some(rt);
            }
            Err(e) => {
                log_error!("Failed to create Tokio runtime: {:?}", e);
            }
        }
    });

    let runtime_guard = TOKIO_RUNTIME.lock().unwrap();
    let runtime = match runtime_guard.as_ref() {
        Some(rt) => rt,
        None => {
            log_error!("Tokio runtime not initialized");
            return -2;
        }
    };

    let data_path = PathBuf::from(data_dir_str);
    let cache_dir = data_path.join("cache");
    let state_dir = data_path.join("state");

    std::fs::create_dir_all(&cache_dir).ok();
    std::fs::create_dir_all(&state_dir).ok();

    let outcome: jint = runtime.block_on(async {
        log_info!("Creating Arti client...");

        let mut builder = TorClientConfigBuilder::from_directories(state_dir, cache_dir);

        // Arti's fs-mistrust walks every parent of the state dir and rejects any
        // that has an "unsafe" owner. On Android the app's private filesDir is
        // sandboxed by the OS, so the default strict check is correct. On JVM
        // host runs (TorArtiNativeIntegrationTest) the data dir lives under
        // /tmp and the check trips on container-style ownership of `/` (UID 999
        // etc.). Disable it for non-Android targets — these are the test/dev
        // surface, not a user-facing binary.
        #[cfg(not(target_os = "android"))]
        {
            builder.storage().permissions().dangerously_trust_everyone();
        }

        let config = match builder.build() {
            Ok(c) => c,
            Err(e) => {
                log_error!("Failed to build Tor config: {:?}", e);
                return -3;
            }
        };

        // Create the client WITHOUT waiting for the directory.
        //
        // `create_bootstrapped` used to block this JNI call — and the Kotlin lifecycle lock it
        // holds — for the entire directory download: 12.6-33.8s measured on a cold install, during
        // which the app treated Tor as absent and every relay dial failed into backoff.
        //
        // With `BootstrapBehavior::OnDemand` the client is usable the moment it exists and each
        // stream waits for the directory itself, so a connect() issued mid-download queues on its
        // own circuit instead of failing. Readiness stops being a global gate the app has to poll
        // and becomes a property of individual connections.
        //
        // The `_async` variant is deliberate: it allows a short grace period for the state file
        // lock, which a destroy()/initialize() cycle needs, where the sync one waits not at all.
        let client = match TorClient::builder()
            .config(config)
            .bootstrap_behavior(BootstrapBehavior::OnDemand)
            .create_unbootstrapped_async()
            .await
        {
            // Arti 2.4.0 made every TorClient constructor return an Arc<TorClient>
            // (TorClient itself is no longer Clone), so there is nothing to wrap here.
            Ok(c) => c,
            Err(e) => {
                log_error!("Failed to create Tor client: {:?}", e);
                return -3;
            }
        };

        *ARTI_CLIENT.lock().unwrap() = Some(Arc::clone(&client));

        // Start the download now rather than leaving it for the first stream to trigger, so it
        // overlaps the login screen exactly as it used to, and publish the outcome so Kotlin can
        // move the status from Bootstrapping to Active at the right moment.
        let handle = tokio::spawn(async move {
            let started = std::time::Instant::now();
            match client.bootstrap().await {
                Ok(()) => log_info!(
                    "Arti directory bootstrap complete after {}ms",
                    started.elapsed().as_millis()
                ),
                // Not fatal and deliberately not latched anywhere: with OnDemand the next stream
                // retries the bootstrap on its own, and isBootstrapped() reports live readiness, so
                // a recovery after this point is picked up without us having to model it.
                Err(e) => log_error!(
                    "Arti directory bootstrap failed after {}ms (streams will retry): {:?}",
                    started.elapsed().as_millis(),
                    e
                ),
            }
        });
        if let Some(previous) = BOOTSTRAP_TASK.lock().unwrap().replace(handle) {
            previous.abort();
        }

        log_info!("Arti client created (unbootstrapped; directory downloading in background)");
        0
    });

    if outcome == 0 {
        log_info!("Arti initialized successfully");
    }
    outcome
}

// ============================================================================
// Streams — Kotlin's TorSocketImpl drives these; nothing listens on a port
// ============================================================================

/// One Tor stream, split so a reader thread and a writer thread (a WebSocket has both) never
/// wait on each other.
struct TorStream {
    reader: tokio::sync::Mutex<DataReader>,
    writer: tokio::sync::Mutex<DataWriter>,
    closed: AtomicBool,
    closing: Notify,
}

impl TorStream {
    fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.closing.notify_waiters();
    }

    /// Resolves once close() has run, including when it ran before this was polled.
    async fn until_closed(&self) {
        let notified = self.closing.notified();
        tokio::pin!(notified);
        notified.as_mut().enable();
        if self.closed.load(Ordering::SeqCst) {
            return;
        }
        notified.await;
    }

    /// Bytes read into `buf`, or an `IO_*` code. Dropping a pending `read` loses no data, which
    /// is what lets OkHttp's 1 ms health probe time out without corrupting the stream.
    async fn read(&self, buf: &mut [u8], timeout: Option<Duration>) -> Result<usize, jint> {
        let reading = async {
            let mut reader = self.reader.lock().await;
            reader.read(buf).await
        };
        let outcome = tokio::select! {
            r = with_timeout(timeout, reading) => r,
            _ = self.until_closed() => return Err(IO_CLOSED),
        };
        match outcome {
            None => Err(IO_TIMEOUT),
            Some(Ok(0)) => Err(IO_EOF),
            Some(Ok(n)) => Ok(n),
            Some(Err(_)) if self.closed.load(Ordering::SeqCst) => Err(IO_CLOSED),
            Some(Err(e)) => {
                log_error!("Tor stream read failed: {:?}", e);
                Err(IO_FAILED)
            }
        }
    }

    /// Writes and flushes: Arti holds bytes until a cell fills, and TLS above us does not
    /// always flush, so an unflushed handshake would stall.
    async fn write(&self, data: &[u8]) -> jint {
        let writing = async {
            let mut writer = self.writer.lock().await;
            writer.write_all(data).await?;
            writer.flush().await
        };
        let outcome = tokio::select! {
            r = writing => r,
            _ = self.until_closed() => return IO_CLOSED,
        };
        match outcome {
            Ok(()) => 0,
            Err(_) if self.closed.load(Ordering::SeqCst) => IO_CLOSED,
            Err(e) => {
                log_error!("Tor stream write failed: {:?}", e);
                IO_FAILED
            }
        }
    }
}

async fn with_timeout<F: std::future::Future>(timeout: Option<Duration>, future: F) -> Option<F::Output> {
    match timeout {
        Some(limit) => tokio::time::timeout(limit, future).await.ok(),
        None => Some(future.await),
    }
}

/// `None` for 0 and below, which Java's soTimeout and connectTimeout mean as "wait forever".
fn timeout_from_millis(ms: jint) -> Option<Duration> {
    u64::try_from(ms).ok().filter(|&ms| ms > 0).map(Duration::from_millis)
}

fn runtime_handle() -> Option<tokio::runtime::Handle> {
    TOKIO_RUNTIME.lock().unwrap().as_ref().map(|rt| rt.handle().clone())
}

fn stream_for(handle: jlong) -> Option<Arc<TorStream>> {
    STREAMS.lock().unwrap().get(&handle).cloned()
}

fn isolation_token(group: jint) -> IsolationToken {
    let tokens = ISOLATION.get_or_init(|| [IsolationToken::new(), IsolationToken::new()]);
    tokens[if group == 1 { 1 } else { 0 }]
}

/// Maps an Arti connect failure onto the `CONNECT_*` code Kotlin turns into a distinct exception,
/// so the caller can tell "this relay is gone" from "this circuit had a bad minute".
///
/// Note on name-resolution failures: Arti documents `RemoteHostResolutionFailed` as
/// retryable, because an exit's resolver failing is not proof the name is dead. We still map
/// it to host-not-found, because the caller's response is a bounded backoff, not permanent
/// condemnation. Probing the relays that produced this error found 17 of 21 to be NXDOMAIN
/// from a normal resolver, so the conservative reading costs far more than it saves.
fn connect_failure_code(e: &arti_client::Error) -> jlong {
    use arti_client::ErrorKind::*;

    match e.kind() {
        RemoteHostNotFound | RemoteHostResolutionFailed => CONNECT_HOST_NOT_FOUND,
        RemoteConnectionRefused => CONNECT_REFUSED,
        // The exit refuses this destination by policy; another exit may allow it.
        ExitPolicyRejected => CONNECT_EXIT_POLICY,
        RemoteNetworkFailed => CONNECT_NETWORK_UNREACHABLE,
        ExitTimeout | RemoteNetworkTimeout => CONNECT_TIMEOUT,
        InvalidStreamTarget | ForbiddenStreamTarget => CONNECT_BAD_TARGET,
        // Anything else stays deliberately vague rather than being mislabelled.
        _ => CONNECT_FAILED,
    }
}

/// Open a Tor stream to `host:port`, resolved by the exit. Returns a handle (> 0) or a
/// negative `CONNECT_*` code. Blocks the calling thread, which must not be a Tokio worker.
#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_connect<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    host: JString<'caller>,
    port: jint,
    isolation: jint,
    timeout_ms: jint,
) -> jlong {
    let host: Option<String> = env
        .with_env(|env| -> JniResult<Option<String>> { Ok(host.try_to_string(env).ok()) })
        .resolve::<LogErrorAndDefault>();
    let (Some(host), Some(port)) = (host, u16::try_from(port).ok().filter(|&p| p != 0)) else {
        return CONNECT_BAD_TARGET;
    };

    let client = match ARTI_CLIENT.lock().unwrap().as_ref() {
        Some(c) => Arc::clone(c),
        None => return CONNECT_NO_CLIENT,
    };
    let Some(runtime) = runtime_handle() else {
        return CONNECT_NO_CLIENT;
    };

    let mut prefs = StreamPrefs::new();
    prefs.set_isolation(isolation_token(isolation));
    let connecting = client.connect_with_prefs((host.as_str(), port), &prefs);
    let stream = match runtime.block_on(with_timeout(timeout_from_millis(timeout_ms), connecting)) {
        Some(Ok(stream)) => stream,
        Some(Err(e)) => {
            let code = connect_failure_code(&e);
            log_error!(
                "Failed to connect through Tor to {}:{}: kind={:?} code={} {:?}",
                host, port, e.kind(), code, e
            );
            return code;
        }
        None => {
            log_error!("Timed out connecting through Tor to {}:{} after {}ms", host, port, timeout_ms);
            return CONNECT_TIMEOUT;
        }
    };

    let (reader, writer) = stream.split();
    let handle = NEXT_STREAM.fetch_add(1, Ordering::Relaxed);
    STREAMS.lock().unwrap().insert(
        handle,
        Arc::new(TorStream {
            reader: tokio::sync::Mutex::new(reader),
            writer: tokio::sync::Mutex::new(writer),
            closed: AtomicBool::new(false),
            closing: Notify::new(),
        }),
    );
    handle
}

/// Read up to `length` bytes into `buffer[offset..]`. Returns the count or a negative `IO_*`
/// code. `timeout_ms` 0 waits forever.
#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_read<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    buffer: JByteArray<'caller>,
    offset: jint,
    length: jint,
    timeout_ms: jint,
) -> jint {
    let (Some(stream), Some(runtime)) = (stream_for(handle), runtime_handle()) else {
        return IO_CLOSED;
    };
    let mut data = vec![0u8; usize::try_from(length).unwrap_or(0).min(MAX_READ_BYTES)];
    let n = match runtime.block_on(stream.read(&mut data, timeout_from_millis(timeout_ms))) {
        Ok(n) => n,
        Err(code) => return code,
    };

    // SAFETY: u8 and i8 have the same size and alignment; jbyte is i8.
    let bytes: &[i8] = unsafe { std::slice::from_raw_parts(data.as_ptr().cast::<i8>(), n) };
    let copied: bool = env
        .with_env(|env| -> JniResult<bool> {
            buffer.set_region(env, offset, bytes)?;
            Ok(true)
        })
        .resolve::<LogErrorAndDefault>();
    if copied { n as jint } else { IO_FAILED }
}

/// Write and flush `buffer[offset..offset + length]`. Returns 0 or a negative `IO_*` code.
#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_write<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    buffer: JByteArray<'caller>,
    offset: jint,
    length: jint,
) -> jint {
    let mut bytes = vec![0i8; usize::try_from(length).unwrap_or(0)];
    let copied: bool = env
        .with_env(|env| -> JniResult<bool> {
            buffer.get_region(env, offset, &mut bytes)?;
            Ok(true)
        })
        .resolve::<LogErrorAndDefault>();
    if !copied {
        return IO_FAILED;
    }
    let (Some(stream), Some(runtime)) = (stream_for(handle), runtime_handle()) else {
        return IO_CLOSED;
    };
    // SAFETY: i8 and u8 have the same size and alignment.
    let data: &[u8] = unsafe { std::slice::from_raw_parts(bytes.as_ptr().cast::<u8>(), bytes.len()) };
    runtime.block_on(stream.write(data))
}

/// End a stream and wake its blocked reads and writes. Arti sends END once both halves drop,
/// which happens as soon as those calls return. Unknown handles are ignored.
#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_closeStream(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if let Some(stream) = STREAMS.lock().unwrap().remove(&handle) {
        stream.close();
    }
}

/// Directory-download progress in permille (0..1000), or -1 when there is no client.
///
/// Lets Kotlin tell a slow download from a stalled one. A timeout cannot: measured cold downloads
/// ran 12.6-34.4s on the same hardware and network, so any fixed patience is either short enough to
/// kill healthy ones or long enough to sit on a dead one. Forward progress separates them exactly.
///
/// Deliberately does NOT surface `BootstrapStatus::blocked()`. Arti documents it as best-effort and
/// warns it "may declare that Arti is stuck for reasons that are incorrect; or it may declare that
/// the client is not stuck when in fact no progress is being made" — acting on that would trade a
/// measurable signal for a guess.
#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_bootstrapProgressPermille(
    _env: EnvUnowned,
    _class: JClass,
) -> jint {
    match ARTI_CLIENT.lock().unwrap().as_ref() {
        Some(client) => (client.bootstrap_status().as_frac() * 1000.0).clamp(0.0, 1000.0) as jint,
        None => -1,
    }
}

/// Live readiness: 1 = ready for traffic, 0 = not yet, -1 = no client at all.
///
/// Asks Arti itself (`bootstrap_status().ready_for_traffic()`, a cheap borrow-and-clone of a small
/// struct) rather than latching the outcome of the one background `bootstrap()` call. That call can
/// fail while the client stays perfectly usable — with OnDemand the next stream just retries — so a
/// latched failure would report "not bootstrapped" forever against a Tor that actually works,
/// leaving the UI wrong and the exit-rotation self-heal disabled.
#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_isBootstrapped(
    _env: EnvUnowned,
    _class: JClass,
) -> jint {
    match ARTI_CLIENT.lock().unwrap().as_ref() {
        Some(client) => {
            if client.bootstrap_status().ready_for_traffic() {
                1
            } else {
                0
            }
        }
        None => -1,
    }
}

/// Destroy the TorClient — used by self-heal paths in Kotlin when Tor is
/// stuck and the in-memory state (guards, circuits) needs to be rebuilt
/// from scratch. Closes every open stream, then drops the static Arc so
/// Arti's state file lock can be released. The next call to [initialize]
/// will create a fresh TorClient (and re-bootstrap).
#[no_mangle]
pub extern "C" fn Java_dev_zapstore_app_transport_ArtiNative_destroy(
    _env: EnvUnowned,
    _class: JClass,
) -> jint {
    log_info!("Destroying Arti client");

    // The background directory download holds an Arc<TorClient> for as long as it runs —
    // which on a dead network is indefinitely. Abort it or the state file lock outlives this
    // destroy() and the next initialize() cannot take it.
    if let Some(h) = BOOTSTRAP_TASK.lock().unwrap().take() {
        h.abort();
    }

    // Wake every blocked read and write so their threads return and drop the stream halves.
    let streams = std::mem::take(&mut *STREAMS.lock().unwrap());
    for stream in streams.values() {
        stream.close();
    }
    drop(streams);

    // Give tokio a moment to cancel the bootstrap task and let woken calls return, so their
    // references are released before we drop our static Arc.
    if let Some(rh) = runtime_handle() {
        rh.block_on(async {
            tokio::time::sleep(Duration::from_millis(500)).await;
        });
    }

    // Drop the static Arc. If a connect() in flight still holds a clone, the TorClient stays
    // alive until it returns — in which case the next initialize() fails and Kotlin retries.
    // (It no longer wipes all Arti data inline: that turned a transient failure into a lost
    // guard sample and a lost consensus.)
    let _ = ARTI_CLIENT.lock().unwrap().take();

    log_info!("Arti client destroyed");
    0
}