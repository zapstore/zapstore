import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/device_key_service.dart';

const kPrivateEventPowDifficulty = 16;

final privateEventPowExecutorProvider = Provider<IsolateProofOfWorkExecutor>((
  ref,
) {
  final executor = IsolateProofOfWorkExecutor();
  ref.onDispose(executor.dispose);
  return executor;
});

final devicePrivateEventServiceProvider = Provider<DevicePrivateEventService>((
  ref,
) {
  return DevicePrivateEventService(
    ref,
    executor: ref.watch(privateEventPowExecutorProvider),
  );
});

/// Enforces the signing and publication contract for device-private events.
class DevicePrivateEventService {
  DevicePrivateEventService(
    this.ref, {
    required ProofOfWorkExecutor executor,
    this.difficulty = kPrivateEventPowDifficulty,
    this.timeout = const Duration(seconds: 10),
    this.maxAttempts = 1 << 21,
    this.batchSize = 512,
  }) : _executor = executor;

  final Ref ref;
  final ProofOfWorkExecutor _executor;
  final int difficulty;
  final Duration timeout;
  final int maxAttempts;
  final int batchSize;

  String get devicePubkey {
    final pubkey = ref.read(devicePubkeyProvider);
    if (pubkey == null) {
      throw const DevicePrivateEventException('Device key is unavailable.');
    }
    return pubkey;
  }

  Signer get deviceSigner {
    final signer = ref.read(Signer.signerProvider(devicePubkey));
    if (signer == null) {
      throw const DevicePrivateEventException('Device signer is unavailable.');
    }
    return signer;
  }

  ProofOfWorkOptions get proofOfWork => ProofOfWorkOptions(
    difficulty: difficulty,
    timeout: timeout,
    maxAttempts: maxAttempts,
    batchSize: batchSize,
    executor: _executor,
  );

  Future<String> encryptToDevice(String plaintext) =>
      deviceSigner.nip44Encrypt(plaintext, devicePubkey);

  Future<String> decryptFromDevice(String ciphertext) =>
      deviceSigner.nip44Decrypt(ciphertext, devicePubkey);

  Future<String> encryptFor(String plaintext, String recipientPubkey) =>
      deviceSigner.nip44Encrypt(plaintext, recipientPubkey);

  Future<E> signAndSave<E extends Model<dynamic>>(
    PartialModel<E> partial, {
    bool publish = true,
  }) async {
    _validatePartial(partial);
    final signer = deviceSigner;
    final signed = await partial.signWith(signer, proofOfWork: proofOfWork);
    _validateSigned(signed);

    final storage = ref.read(storageNotifierProvider.notifier);
    final saved = await storage.save({signed});
    if (!saved) {
      throw const DevicePrivateSaveException(
        'Could not save private data locally.',
      );
    }

    if (publish) {
      final response = await storage.publish({signed}, relays: 'AppCatalog');
      final accepted =
          response.results[signed.event.id]?.any((result) => result.accepted) ??
          false;
      if (!accepted) {
        throw const DevicePrivatePublishException(
          'Saved locally, but no AppCatalog relay accepted the event.',
        );
      }
    }
    return signed;
  }

  DateTime nextReplaceableTimestamp(
    DateTime? existing, {
    DateTime Function()? clock,
  }) {
    final now = (clock ?? DateTime.now)();
    final candidate = DateTime.fromMillisecondsSinceEpoch(
      (now.millisecondsSinceEpoch ~/ 1000) * 1000,
      isUtc: now.isUtc,
    );
    if (existing == null || candidate.isAfter(existing)) return candidate;
    return existing.add(const Duration(seconds: 1));
  }

  void cancelMining() {
    final executor = _executor;
    if (executor is IsolateProofOfWorkExecutor) {
      executor.cancelAll();
    }
  }

  void _validatePartial(PartialModel<dynamic> partial) {
    final event = partial.event;
    switch (event.kind) {
      case 30267:
        if (event.content.isEmpty) {
          throw const DevicePrivateEventException(
            'Public app stacks must not use the private event signer.',
          );
        }
        if (event.containsTag('h')) {
          throw const DevicePrivateEventException(
            'Encrypted app stacks must not carry a community h tag.',
          );
        }
      case 30078:
        final identifier = event.identifier;
        if (identifier != kSettingsIdentifier &&
            identifier != kTrustedSignersIdentifier) {
          throw DevicePrivateEventException(
            'Unsupported private CustomData identifier: $identifier',
          );
        }
      default:
        throw DevicePrivateEventException(
          'Unsupported private event kind: ${event.kind}',
        );
    }
  }

  void _validateSigned(Model<dynamic> signed) {
    if (signed.pubkey != devicePubkey) {
      throw const DevicePrivateEventException(
        'Private event was not signed by the device key.',
      );
    }
    if (!verifySignedEvent(ref, signed.event)) {
      throw const DevicePrivateEventException(
        'Private event signature or event ID is invalid.',
      );
    }
    if (!Nip13.isValid(signed.event, minimumDifficulty: difficulty)) {
      throw DevicePrivateEventException(
        'Private event does not meet $difficulty-bit proof of work.',
      );
    }
  }
}

/// Verifies both the canonical Nostr ID and its BIP-340 signature.
bool verifySignedEvent(Ref ref, EventBase event) {
  try {
    final map = event.toMap();
    final pubkey = map['pubkey'];
    if (pubkey is! String) return false;
    final partial = PartialEvent<Model<dynamic>>(map, event.kind)
      ..tags = [for (final tag in event.tags) List<String>.of(tag)];
    return map['id'] == Utils.getEventId(partial, pubkey) &&
        ref.read(verifierProvider).verify(map);
  } catch (_) {
    return false;
  }
}

class DevicePrivateEventException implements Exception {
  const DevicePrivateEventException(this.message);

  final String message;

  @override
  String toString() => message;
}

final class DevicePrivateSaveException extends DevicePrivateEventException {
  const DevicePrivateSaveException(super.message);
}

final class DevicePrivatePublishException extends DevicePrivateEventException {
  const DevicePrivatePublishException(super.message);
}
