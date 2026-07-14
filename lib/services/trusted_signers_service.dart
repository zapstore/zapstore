import 'dart:convert';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';

/// Simple helper service to manage trusted signers persisted via CustomData
class TrustedSignersService {
  const TrustedSignersService(this.ref);

  final Ref ref;

  /// Returns whether a signer pubkey is trusted by this device.
  Future<bool> isSignerTrusted(String signerPubkey) async {
    final trusted = await _loadTrustedSigners();
    return trusted.contains(signerPubkey);
  }

  /// Adds a signer pubkey to the device-private trusted list.
  Future<void> addTrustedSigner(String signerPubkey) async {
    final trusted = await _loadTrustedSigners();
    if (trusted.contains(signerPubkey)) return;
    trusted.add(signerPubkey);
    await _saveTrustedSigners(trusted);
  }

  Future<void> _saveTrustedSigners(Set<String> trusted) async {
    final privateEvents = ref.read(devicePrivateEventServiceProvider);
    final content = jsonEncode({'trusted': trusted.toList()});
    final encrypted = await privateEvents.encryptToDevice(content);
    final partial = PartialCustomData(
      identifier: kTrustedSignersIdentifier,
      content: encrypted,
    );
    await privateEvents.signAndSave(partial, publish: false);
  }

  Future<Set<String>> _loadTrustedSigners() async {
    final devicePubkey = ref.read(devicePubkeyProvider);
    if (devicePubkey == null) return <String>{};

    try {
      final request = Request<CustomData>([
        RequestFilter<CustomData>(
          authors: {devicePubkey},
          tags: {
            '#d': {kTrustedSignersIdentifier},
          },
          limit: 1,
        ),
      ]);

      final storage = ref.read(storageNotifierProvider.notifier);
      final List<CustomData> models = await storage.query(
        request,
        source: const LocalSource(),
      );

      if (models.isEmpty) return <String>{};
      final model = models.first;
      final decrypted = await ref
          .read(devicePrivateEventServiceProvider)
          .decryptFromDevice(model.content);
      final map = jsonDecode(decrypted) as Map<String, dynamic>;
      final list =
          (map['trusted'] as List?)?.cast<String>() ?? const <String>[];
      return list.toSet();
    } catch (_) {
      return <String>{};
    }
  }

  /// Imports the legacy local Amber-authored preference without publishing it.
  Future<void> migrateLegacyAmberRecord(Signer amberSigner) async {
    final storage = ref.read(storageNotifierProvider.notifier);
    final legacy = await storage.query(
      RequestFilter<CustomData>(
        authors: {amberSigner.pubkey},
        tags: {
          '#d': {kTrustedSignersIdentifier},
        },
        limit: 1,
      ).toRequest(),
      source: const LocalSource(),
    );
    if (legacy.isEmpty) return;

    try {
      final map = jsonDecode(legacy.first.content) as Map<String, dynamic>;
      final oldTrusted =
          (map['trusted'] as List?)?.whereType<String>().toSet() ?? const {};
      if (oldTrusted.isEmpty) return;
      final merged = {...await _loadTrustedSigners(), ...oldTrusted};
      await _saveTrustedSigners(merged);
    } catch (_) {
      // Malformed legacy local preferences are ignored.
    }
  }
}

final trustServiceProvider = Provider<TrustedSignersService>(
  (ref) => TrustedSignersService(ref),
);
