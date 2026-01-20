# Zapstore

The open app store powered by your social network

[https://zapstore.dev](https://zapstore.dev)

## Build from source

```bash
flutter pub get
flutter build apk --split-per-abi --debug
```

APK will be available at `build/app/outputs/flutter-apk`.

<a href="https://zapstore.dev/apps/naddr1qvzqqqr7pvpzq7xwd748yfjrsu5yuerm56fcn9tntmyv04w95etn0e23xrczvvraqqgxgetk9eaxzurnw3hhyefwv9c8qakg5jt">
  <img src="./assets/images/badge.png"
  alt="Get it on ZapStore" width="200">
</a>

> **Note on releases and reproducibility**
>
> Zapstore Android release APKs are expected to be **bit-for-bit reproducible** from
> the same git commit.
>
> Builds intended for verification (e.g. F-Droid or release auditing) must follow
> the pinned toolchain and deterministic build invariants described in `INVARIANTS.md`.

## Contributing

Unless it's a minor fix, please reach out to us first before working on any contribution!

We will have a clearer process to contribute once we are out of beta.

## Testing

This project is tested with [BrowserStack](https://www.browserstack.com/).

## License

MIT
