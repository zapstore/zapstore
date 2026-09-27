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

That runs `./gradlew assembleDebug`. The debug APK lands in `build/outputs/apk/debug/`. Set `CATALOG_BUNDLE` to a `tar.zst` seed and `make build`, `make run`, and `make release` copy it to `src/main/assets/bundle-0-1.tar.zst`. Without it, the APK ships no catalog bundle. First launch then requests `GET /deltas?from=0` from `wss://brelay.zapstore.dev` and imports that bundle.

Tor uses a size-optimized `libarti_android.so` from `tools/arti-build` (Amethyst's JNI wrapper, zapstore package names). Rebuild the arm64-v8a library and commit it when bumping Arti:

```bash
make vendor
```

That needs rustup, `cargo-ndk` at the version in `tools/arti-build/CARGO_NDK_VERSION`, and the NDK revision in `tools/arti-build/ANDROID_NDK_VERSION`.

```bash
make run            # installDebug, launch, then press r to rebuild
make run-release    # assembleRelease (R8), install, launch
```

`make install` clears app data and embeds `CATALOG_BUNDLE` when that path is set. `make build` only builds.

The catalog relay URLs and signer come from that bundle's signed manifest, not from the build. `make run-release` reverses port 3334 so a bundle that lists `ws://127.0.0.1:3334` can reach a relay on the host.

Release builds run R8 (minify + optimize + shrink resources, English locales only). Mapping lands in `build/outputs/mapping/release/`.

Or call Gradle directly (a seed in `src/main/assets/` is packaged if present):

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
```

`./gradlew test` runs unit tests for the app and `iolite`. Instrumented UI tests need a connected device:

```bash
./gradlew connectedDebugAndroidTest
```

## Layout

- `src/main/kotlin/dev/zapstore/app/` — `ZapstoreApplication` (singletons), `MainActivity` (routes), `AppConfig` (build-time endpoints and query options)
  - `screens/` — one `*Screen.kt` + `*ViewModel.kt` per destination (Home, AppDetail, StackDetail, Profile, Updates, Settings)
  - `components/` — shared UI on Iolite records (`AppCard`, `StackCard`, `AppList`, `ProfileImage`, primitives)
  - `catalog/` — `CatalogSync` (foreground `GET /deltas`) and installed-package matching
  - `transport/` — Tor/direct network runtime
- `tools/arti-build/` — reproducible Arti JNI build; `make vendor` writes `src/main/jniLibs/arm64-v8a/libarti_android.so`
- `iolite/` — local-first Nostr client: SQLite rows derived from verified events, relay sessions, typed queries

## Contributing

Minor fixes: PRs welcome. For larger changes, please reach out first while the project is in beta.
