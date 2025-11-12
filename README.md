[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/zapstore/zapstore)

# zapstore

## App developers

Looking to publish your app on Zapstore? Start with the [App Developer Guide](docs/app-developer-guide.md) for prerequisites, publishing workflow, trust model details, and troubleshooting tips. For a deeper mental model of how the Android client, CLI, Blossom, and relays interact, see the [Architecture overview](docs/architecture.md).

## Build from source

```bash
flutter pub get
flutter build apk --split-per-abi --debug
```

APK will be available at `build/app/outputs/flutter-apk`.

## Contributing

Unless it's a minor fix, please reach out to us first before working on any contribution!

We will have a clearer process to contribute once we are out of beta.

## Testing

This project is tested with [BrowserStack](https://www.browserstack.com/).

## License

MIT
