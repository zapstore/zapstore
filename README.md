# Zapstore

[![Release](https://img.shields.io/github/v/release/zapstore/zapstore)](https://github.com/zapstore/zapstore/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202026.05.01-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)

**Zapstore is an open Android app store where apps can be published directly by developers and curated by communities.**

It combines app discovery, publisher identity, direct APK distribution, and social trust signals into a different model for Android app distribution.

This repo is the [Kotlin](https://kotlinlang.org) / [Jetpack Compose](https://developer.android.com/compose) client for [Zapstore](https://zapstore.dev). Catalog listings, curated stacks, releases, and publisher profiles sync from Nostr relays through the local `purplequartz` module.

## Get the app

The production APK is still published from the [Flutter client](https://github.com/zapstore/zapstore/releases). This tree is the Kotlin rewrite (`dev.zapstore.beta`).

[GitHub Releases](https://github.com/zapstore/zapstore/releases) · [zapstore.dev](https://zapstore.dev)

## Requirements

- JDK **21**
- Android SDK (compile / target API **37**, min SDK **29**)
- A device or emulator for `make run`

## Build from source

```bash
make build
```

That runs `./gradlew assembleDebug`. The debug APK lands in `build/outputs/apk/debug/`.

```bash
make emulator   # Pixel_9 AVD (created on first run)
make run        # installDebug, launch, then press r to rebuild
```

Or call Gradle directly:

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
```

`./gradlew test` runs unit tests for the app and `purplequartz`. Instrumented UI tests need a connected device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

## Layout

- `src/main/kotlin/dev/zapstore/app/` — Compose UI, navigation, view models, and catalog wiring
- `purplequartz/` — local-first Nostr client (SQLite event store, relay sessions, outbox routing) on top of [Quartz](https://github.com/vitorpamplona/amethyst)

## Contributing

Minor fixes: PRs welcome. For larger changes, please reach out first while the project is in beta.
