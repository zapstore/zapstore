# Zapstore

[![Release](https://img.shields.io/github/v/release/zapstore/zapstore)](https://github.com/zapstore/zapstore/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202026.05.01-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)

Zapstore is a private, open app store for degoogled Android, built for GrapheneOS, LineageOS, and similar systems. Browsing, installing, and updating are free and need no account. The [help pages](https://zapstore.dev/help) explain how to use it.

## What it does

- **Shows what it knows before you install.** The install screen shows who signed the app, whether the developer [claimed](https://zapstore.dev/help/security#claimed-and-unclaimed) it, who follows that developer, and a [security report](https://zapstore.dev/help/security): Google services, trackers, ads, permissions, and whether the source is available.
- **Keeps your list on the device.** The catalog syncs to the phone as a signed bundle. Search, update checks, and your installed apps stay local. Nothing is reported about what you install. See [Privacy](https://zapstore.dev/help/privacy).
- **Updates in the background** on the interval you pick. Zapstore installs updates without a prompt when Android allows it.
- **Takes any [catalog](https://zapstore.dev/help/catalogs).** One default catalog ships, and you can remove it or add others by key. When two catalogs list an app, the one you added first wins.
- **Pays developers directly.** [Zap](https://zapstore.dev/help/social#zaps) an app from a connected wallet. Developers pay no fee on what they earn.
- **Has a social layer you can turn off.** Comments, zaps, and stacks (shared lists of apps) are public. Turn Social off in Settings and the app shows only the catalog. See [Social](https://zapstore.dev/help/social).
- **Reaches the network over [Tor](https://zapstore.dev/help/privacy#using-tor)** if you choose it in Settings. The default is a direct connection, which exposes your IP address to relays and download hosts.

Zapstore finds apps and lists them. A developer who claims an app takes priority over that listing, publishes with [`zsp`](https://zapstore.dev/help/publish), and can remove the app. The catalog lists `arm64-v8a` apps only.

## Under the hood

This repo is the [Kotlin](https://kotlinlang.org) / [Jetpack Compose](https://developer.android.com/compose) client (`dev.zapstore.app`). Catalog listings, stacks, releases, and publisher profiles are Nostr events. They sync from the catalog relay through the local `iolite` module, and [Blossom](https://github.com/hzrd149/blossom) serves the APKs. [Protocols](https://zapstore.dev/help/protocols) explains Nostr, Blossom, and Lightning.

## Get the app

Download it from [GitHub Releases](https://github.com/zapstore/zapstore/releases) or [zapstore.dev](https://zapstore.dev). [Getting started](https://zapstore.dev/help/getting-started) walks through the first install.

## Build

You need JDK 21 and the Android SDK. Running the app needs an `arm64-v8a` device.

```bash
make build     # debug APK in build/outputs/apk/debug/
make run       # install, launch, press r to rebuild
make reload    # rebuild and relaunch, keeping app data
make help      # every target
```

`./gradlew test` runs the unit tests. `make install` clears app data.

## Verify a release

Each release APK rebuilds byte for byte from its tag. You need git, python3, and Docker.

```bash
git clone https://github.com/zapstore/zapstore && cd zapstore
tools/release/verify.sh <version> https://github.com/zapstore/zapstore/releases/download/<version>/zapstore-<version>.apk
```

`SAME: sha256 …` means the published file matches the rebuild. For the bundle APK (the one with the catalog included), use `https://zapstore.dev/downloads/zapstore-<version>-bundle.apk`.

The first run is slow because it downloads the Android SDK. On an Apple Silicon Mac, set `PLATFORM=linux/arm64` for a faster run.

## Contributing

Open issues only. Pull requests are not welcome.

An issue must be prefaced with a human paragraph. AI-assisted text can follow that paragraph. Issues without it will be ignored.

## License

[MIT](https://opensource.org/licenses/MIT).
