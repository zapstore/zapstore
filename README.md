# Zapstore

[![Release](https://img.shields.io/github/v/release/zapstore/zapstore)](https://github.com/zapstore/zapstore/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202026.05.01-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)

**Zapstore is an open Android app store where apps can be published directly by developers and curated by communities.**

It combines app discovery, publisher identity, direct APK distribution, and social trust signals into a different model for Android app distribution.

This repo is the [Kotlin](https://kotlinlang.org) / [Jetpack Compose](https://developer.android.com/compose) client for [Zapstore](https://zapstore.dev). Catalog listings, curated stacks, releases, and publisher profiles sync from Nostr relays through the local `iolite` module.

## Get the app

The production APK is still published from the [Flutter client](https://github.com/zapstore/zapstore/releases). This tree is the Kotlin rewrite (`dev.zapstore.beta`).

[GitHub Releases](https://github.com/zapstore/zapstore/releases) · [zapstore.dev](https://zapstore.dev)

## Requirements

- JDK **21**
- Android SDK (compile / target API **37**, min SDK **29**)
- An **arm64-v8a** device for `make run` (this is the only ABI the app ships).

## Build from source

```bash
make build
```

That runs `./gradlew assembleDebug`. The debug APK lands in `build/outputs/apk/debug/` and ships no catalog bundle, so the first launch requests `GET /v1/bundle?from=0` from `wss://relay.zapstore.dev` and imports it. `make help` lists every target.

A debug build can ship a bundle of your choosing, for a local relay or a test:

```bash
CATALOG_RELAY=ws://127.0.0.1:3334 CATALOG_PUBKEY=<hex> CATALOG_BUNDLE=path/to/bundle-0-1.tar.zst make run
```

That passes `-PcatalogBundle=<file>` to Gradle. Release builds take `none` or `pinned` and refuse a file; see "Releasing".

Tor uses a size-optimized `libarti_android.so` from `tools/arti-build` (Amethyst's JNI wrapper, zapstore package names). Rebuild the arm64-v8a library and commit it when bumping Arti:

```bash
make vendor
```

That needs rustup, `cargo-ndk` at the version in `tools/arti-build/CARGO_NDK_VERSION`, and the NDK revision in `tools/arti-build/ANDROID_NDK_VERSION`.

```bash
make run            # installDebug, launch, then press r to rebuild
make reload         # one-shot build, install, relaunch (keeps app data). For scripts and agents
make watch          # same, on every saved change
```

`make reload` takes about 3s with a warm Gradle daemon. It prints one line on success. On failure it prints the compiler errors or the crash log and exits non-zero. Run `make run` once before it.

`make install` clears app data. `make build` only builds.

The catalog relay URLs and signer come from that bundle's signed manifest, not from the build. `make reverse` forwards port 3334 so a bundle that lists `ws://127.0.0.1:3334` can reach a relay on the host.

Release builds run R8 (minify + optimize + shrink resources, English locales only). Mapping lands in `build/outputs/mapping/release/`.

Or call Gradle directly:

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
```

`./gradlew test` runs unit tests for the app and `iolite`. Instrumented UI tests need a connected device:

```bash
./gradlew connectedDebugAndroidTest
```

## Verify a release

Each release APK rebuilds byte for byte from its tag. You need git, python3 and a running Docker (or `--local` with your own JDK 21 and Android SDK).

```bash
git clone https://github.com/zapstore/zapstore && cd zapstore
tools/release/verify.sh <version> https://github.com/zapstore/zapstore/releases/download/<version>/zapstore-<version>.apk
tools/release/verify.sh <version> https://zapstore.dev/downloads/zapstore-<version>-bundle.apk
```

The first is the update APK, the second the bundle APK. `SAME: sha256 …` means the published file is the rebuild plus its signature, nothing else. A difference is reported with the byte where it starts.

- The build runs in a pinned container (Temurin JDK 21.0.9, Android platform 37.0, build-tools 36.0.0). Gradle and every dependency are checked against `gradle/verification-metadata.xml`.
- The bundle APK also ships the catalog bundle pinned in `gradle.properties`, fetched by its sha256.
- The first run downloads the Android SDK and dependencies. Later runs reuse the image and a Docker volume.
- On an Apple Silicon Mac the default `linux/amd64` container is emulated and slow. `PLATFORM=linux/arm64` runs the JDK natively.
- Only releases cut with `make cut-mobile-release` can be verified. Earlier ones predate the pin and the pinned build environment.

## Releasing

`make cut-mobile-release` in `infra/` (`infra/misc/cut-mobile-release.sh`) cuts a release. It pins the catalog, builds and signs two APKs from one commit, and puts them where they belong:

| APK | Catalog bundle | For | Published at |
| --- | --- | --- | --- |
| update APK | none | an update from Zapstore | the GitHub release, as `zapstore-<version>.apk`, and Zapstore |
| bundle APK | the pinned one | a new install, which opens with the catalog already there | `https://zapstore.dev/downloads/zapstore-<version>-bundle.apk` |

The pinned catalog bundle sits next to the bundle APK, as `https://zapstore.dev/downloads/catalog-<epoch>.tar.zst`. The relay keeps only its latest epoch, so a build cannot ask it for an old one. The script takes the relay's current full bundle, checks its signer, writes the pin into `gradle.properties` (`catalogPinSha256` and `catalogPinUrl`), and commits it before it builds, so the tag holds what the release is rebuilt from. The tag is annotated (`Release <version> (build <code>)`) and sits on that commit, which is the commit the APKs record. Gradle (`-PcatalogBundle=pinned`) fetches the bundle from that URL and fails unless the sha256 matches. During the release itself it reads the copy the script saved under `build/release/`.

The two files are copied to `/var/lib/zapstore/downloads` on production, which Caddy serves at `/downloads`, over the ssh control socket the other infra scripts use (`infra/README.md`). The script checks the sha256 on the server and the size at the public URL, and it does this before the push, so a failed copy leaves no tag that names a missing file. `make cut-mobile-release-check` only looks at the local tree and that the socket exists.

`tools/release/build-apk.sh bundle|update` is the one recipe for an unsigned APK, and the script and a verifier both use it. It refuses a dirty tree, because the APK records its commit in `META-INF/version-control-info.textproto`.

`libarti_android.so` is committed rather than built here. `tools/arti-build/verify-reproducible.sh` rebuilds it twice and compares.

When you change a dependency, regenerate the checksums with `./gradlew --write-verification-metadata sha256 assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest :iolite:testDebugUnitTest :iolite:assembleDebugAndroidTest lintDebug`. Also add the Linux build tools' checksums from the container, since a Mac only resolves the macOS ones.

## Layout

- `src/main/kotlin/dev/zapstore/app/` — `ZapstoreApplication` (singletons), `MainActivity` (routes), `AppConfig` (build-time endpoints and query options)
  - `screens/` — one `*Screen.kt` + `*ViewModel.kt` per destination (Home, AppDetail, StackDetail, Profile, Updates, Settings)
  - `components/` — shared UI on Iolite records (`AppCard`, `StackCard`, `AppList`, `ProfileImage`, primitives)
  - `catalog/` — `CatalogSync` (foreground `GET /deltas`) and installed-package matching
  - `transport/` — Tor/direct network runtime
- `tools/release/` — `build-apk.sh` builds a release APK and `verify.sh` rebuilds and checks one; `catalogPinSha256` and `catalogPinUrl` in `gradle.properties` are the pinned bundle that `infra/misc/cut-mobile-release.sh` writes
- `tools/arti-build/` — reproducible Arti JNI build; `make vendor` writes `src/main/jniLibs/arm64-v8a/libarti_android.so`
- `iolite/` — local-first Nostr client: SQLite rows derived from verified events, relay sessions, typed queries

## Contributing

Minor fixes: PRs welcome. For larger changes, please reach out first while the project is in beta.
