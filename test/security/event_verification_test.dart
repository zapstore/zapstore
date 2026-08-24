import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/event_verifier.dart';
import 'package:zapstore/utils/debug_utils.dart';

/// The APK the publisher actually built, signed and released.
const _publisherApkHash =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

/// The APK an attacker wants the app to download and install instead.
const _attackerApkHash =
    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';

/// Builds a genuinely signed kind 3063 SoftwareAsset event, as published by an
/// app developer, and returns its wire representation.
Future<Map<String, dynamic>> _signedAssetEvent(
  ProviderContainer container,
) async {
  final signer = Bip340PrivateKeySigner('1' * 64, container.read(refProvider));
  await signer.signIn(setAsActive: false);

  final partial = PartialSoftwareAsset()
    ..appIdentifier = 'com.example.wallet'
    ..version = '1.0.0'
    ..versionCode = 100
    ..hash = _publisherApkHash
    ..urls = {'https://cdn.zapstore.dev/$_publisherApkHash'}
    ..apkCertificateHashes = {'c' * 64}
    ..platforms = {'android-arm64-v8a'};

  final signed = await partial.signWith(signer);
  return signed.event.toMap();
}

/// Replays the publisher's `(pubkey, id, sig)` triple while swapping the
/// payload a relay can control: the APK hash, its download URL and the
/// declared signing certificate.
Map<String, dynamic> _tamperWithPayload(Map<String, dynamic> event) {
  final tags = [
    for (final tag in event['tags'] as List) List<String>.from(tag as List),
  ];
  for (final tag in tags) {
    switch (tag.first) {
      case 'x':
        tag[1] = _attackerApkHash;
      case 'url':
        tag[1] = 'https://cdn.zapstore.dev/$_attackerApkHash';
      case 'apk_certificate_hash':
        tag[1] = 'd' * 64;
    }
  }
  return {...event, 'tags': tags};
}

void main() {
  late ProviderContainer container;

  setUp(() async {
    container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
        zapstoreVerifierOverride,
      ],
    );
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(StorageConfiguration(keepSignatures: true));
  });

  tearDown(() => container.dispose());

  test('accepts a genuine relay event', () async {
    final genuine = await _signedAssetEvent(container);

    expect(container.read(verifierProvider).verify(genuine), isTrue);
  });

  test('rejects an event whose payload was swapped after signing', () async {
    final genuine = await _signedAssetEvent(container);
    final tampered = _tamperWithPayload(genuine);

    // The attacker replays the publisher's identity and signature verbatim.
    expect(tampered['pubkey'], genuine['pubkey']);
    expect(tampered['id'], genuine['id']);
    expect(tampered['sig'], genuine['sig']);

    expect(
      container.read(verifierProvider).verify(tampered),
      isFalse,
      reason:
          'the event id must be recomputed from pubkey, created_at, kind, '
          'tags and content, otherwise a relay can serve any payload under a '
          "publisher's signature",
    );
  });
}
