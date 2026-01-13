# Zapstore

The open app store powered by your social network

[https://zapstore.dev](https://zapstore.dev)

## Build from source

```bash
fvm install
fvm flutter pub get
fvm flutter build apk --split-per-abi --debug
```

Without FVM:

```bash
flutter pub get
flutter build apk --split-per-abi --debug
```

## Reproducible builds

This repo pins:

- **Flutter** via `.fvmrc` (do not use `stable` in FVM)
- **Dart/Flutter dependencies** via `pubspec.lock`
- **Android toolchain** via Gradle/AGP, and expects **JDK 17**

Build "proof" (prints versions + builds):

```bash
# Use JDK 17 (set JAVA_HOME in your environment/CI if needed)
# Examples:
# export JAVA_HOME=$(/usr/libexec/java_home -v 17)            # macOS
# export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64         # Ubuntu/Debian
java -version

fvm install
fvm flutter --version
fvm flutter pub get --enforce-lockfile

fvm flutter build apk --split-per-abi --release
```

APK will be available at `build/app/outputs/flutter-apk`.

<a href="https://zapstore.dev/apps/naddr1qvzqqqr7pvpzq7xwd748yfjrsu5yuerm56fcn9tntmyv04w95etn0e23xrczvvraqqgxgetk9eaxzurnw3hhyefwv9c8qakg5jt">
  <img src="./assets/images/badge.png"
  alt="Get it on ZapStore" width="200">
</a>

## Contributing

Unless it's a minor fix, please reach out to us first before working on any contribution!

We will have a clearer process to contribute once we are out of beta.

## Testing

This project is tested with [BrowserStack](https://www.browserstack.com/).

## License

MIT
