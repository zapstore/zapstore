# Arti Android Build Tools

Custom-built [Arti](https://gitlab.torproject.org/tpo/core/arti) native libraries
for Zapstore. Adapted from [Amethyst's `tools/arti-build`](https://github.com/vitorpamplona/amethyst/tree/main/tools/arti-build):
same size-optimized wrapper, JNI names retargeted to `dev.zapstore.app.transport.ArtiNative`.

The app ships **arm64-v8a only**. `make vendor` from the repo root runs
`./build-arti.sh` and writes:

```
src/main/jniLibs/arm64-v8a/libarti_android.so
```

Commit that `.so` after a successful vendor. Rebuild when bumping
`ARTI_VERSION`, changing `src/lib.rs`, or verifying reproducibility.

## Prerequisites

1. rustup (the toolchain in `rust-toolchain.toml` is installed automatically)
2. `cargo install cargo-ndk --version "$(cat CARGO_NDK_VERSION)" --locked`
3. The exact NDK in `ANDROID_NDK_VERSION`: `sdkmanager "ndk;$(cat ANDROID_NDK_VERSION)"`

## Commands

```bash
./build-arti.sh              # arm64-v8a
./build-arti.sh --clean      # wipe the Arti clone and rebuild
./verify-reproducible.sh     # two clean builds, then diff
```

The compile happens at `/tmp/zapstore-arti-build` so the output is
path-independent. Override with `ARTI_REPRO_DIR` only if you do not need
to match committed bytes.
