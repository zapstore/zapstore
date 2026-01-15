# Reproducible builds (Android)

This project aims to make **byte-for-byte reproducible** Android APK builds.

For reproducibility, always compare **unsigned APKs** (or APKs signed with the exact same keystore + config). Unsigned is simpler and matches how **F-Droid builds (then re-signs)**.

## What is pinned / stabilized

- **Flutter SDK**: pinned via `.fvmrc` (do not use `stable`).
- **Dart/Flutter packages**: pinned via `pubspec.lock`.
- **Gradle**: pinned via `android/gradle/wrapper/gradle-wrapper.properties`.
- **Java**: use **JDK 17** (changing JDK can change bytecode/packaging).
- **SOURCE_DATE_EPOCH**: set to the current commit timestamp to stabilize time-based metadata.
- **Archive determinism**: Gradle archive tasks use stable ordering and no timestamps (`android/build.gradle.kts`).
- **Signing**: `release` is **signed only when a complete `android/key.properties` is present**; otherwise **release builds are unsigned** (`android/app/build.gradle.kts`).

## Requirements

- **FVM** installed
- **JDK 17**
  - Examples (optional):
    - macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`
    - Ubuntu/Debian: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
  - Always print the version: `java -version`

## Build (unsigned release)

Important: ensure `android/key.properties` is **absent** (or incomplete) so the APK is **not signed**.

```bash
# Standard reproducible-build timestamp (ties "build time" to the commit)
export SOURCE_DATE_EPOCH="$(git log -1 --pretty=%ct)"

java -version

fvm install
fvm flutter --version

# Must use the lockfile exactly
fvm flutter pub get --enforce-lockfile

# Build unsigned release APKs
fvm flutter build apk --release --split-per-abi
```

Outputs:

- APKs: `build/app/outputs/flutter-apk/`

## Verify reproducibility

Build twice in clean environments (or two different machines/CI runs), then compare the unsigned artifacts.

Example:

```bash
shasum -a 256 build/app/outputs/flutter-apk/*.apk
```

If hashes differ, use `diffoscope` (recommended) to inspect where the difference comes from.

## Docker proof (recommended)

This is the most \"honest\" way to prove reproducibility because it removes most differences from your host environment.

Requirements:

- Docker
- Internet access on first run (toolchain + dependencies need to be downloaded)

Run:

```bash
bash repro/prove_repro.sh
```

Notes:

- Builds happen in **two isolated linux/amd64 containers** (A and B) with **pinned** Flutter + Android cmdline-tools downloads (SHA-256 verified).
- On Apple Silicon, `linux/amd64` runs under emulation and may be unstable for Flutter/Gradle builds. If you hit Dart VM crashes like `Unexpected EINTR errno`, run this script on an **x86_64 Linux** machine or CI runner (this is the strongest proof anyway).
- The script copies the repo into the container filesystem before building (avoids macOS bind-mount quirks).
- Default builds **arm64-only** using `--split-per-abi` + `--target-platform android-arm64`. Set `REPRO_SPLIT_PER_ABI=0` if you want a single non-split APK, or override `REPRO_TARGET_PLATFORM`/`REPRO_ABI` if you need a different target.
- Outputs are written to `.repro_out/` (ignored by git).

### Expected result (success)

You should see a final line like:

- `OK: reproducible (hashes match)`

And `.repro_out/` should contain two APKs (A and B) plus their `.sha256` files.

### If it fails (what to do)

#### Case A: Hashes differ

The script will end with:

- `ERROR: not reproducible (hashes differ)`

Next steps:

```bash
ls -lah .repro_out
diffoscope .repro_out/*.apk
```

If you don’t have `diffoscope`, do a quick unzip diff:

```bash
rm -rf /tmp/apkA /tmp/apkB
mkdir -p /tmp/apkA /tmp/apkB
unzip -q .repro_out/*-A-*.apk -d /tmp/apkA
unzip -q .repro_out/*-B-*.apk -d /tmp/apkB
diff -qr /tmp/apkA /tmp/apkB | head -n 80
```

#### Case B: Build is accidentally signed

The script will fail if it detects `META-INF/*.RSA|*.DSA|*.EC` inside the APK.

Fix:

- Ensure `android/key.properties` is **not present** (or incomplete) in the repo/checkout used for the proof.

#### Case C: Docker/host instability (Apple Silicon)

If you see Dart VM crashes like `Unexpected EINTR errno` or exit code `134`, run the proof on:

- **x86_64 Linux** (local machine/VM) or a **GitHub Actions** runner (recommended)

### GitHub Actions

There is a manual workflow at `.github/workflows/repro.yml` that runs the same proof on an Ubuntu runner and uploads `.repro_out/` as an artifact.

## Host proof (not preferred)

This repository also includes a host-side proof script:

- `repro/prove_repro_host.sh`

This is **not the preferred method** because it depends on your host environment (Java/Gradle/SDK quirks, filesystem differences, etc.). The Docker proof above is the most reliable and portable way to prove reproducibility.

That said, the host script is useful as a quick local sanity check. It avoids a common false-negative: building in two different directories (e.g. two worktrees like `/tmp/zapstore-A` and `/tmp/zapstore-B`) can embed absolute paths into native libraries (such as `libapp.so`), producing different APK bytes even when the build is otherwise deterministic.

Run:

```bash
bash repro/prove_repro_host.sh
```

Notes:

- Requires the same prerequisites as a normal host build (see **Requirements** above; **JDK 17** recommended).
- Builds happen twice in the **same working directory**, with `build/` and `.dart_tool/` wiped between runs.
- Outputs are written to `.repro_out/host/` and compared via SHA-256.
- If hashes differ, the script prints a short unzip diff and (if `diffoscope` is installed) writes a full report to `.repro_out/host/diffoscope.txt`.
- By default it builds **arm64-only** using `--split-per-abi` + `--target-platform android-arm64`. Override with `REPRO_SPLIT_PER_ABI=0` or change `REPRO_TARGET_PLATFORM`/`REPRO_ABI` if needed.
- If you see `Can't load Kernel binary: Invalid SDK hash.`, you can re-run with `REPRO_CLEAN_SDK=1` to clear Flutter SDK caches and force a fresh precache (slower, but typically removes the warning).

## Notes for F-Droid

- F-Droid will build from source and **re-sign** the APK.
- For reproducibility checks, compare the **unsigned** APK generated by the build step above (same source revision + same pinned toolchain).
