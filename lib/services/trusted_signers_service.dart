import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/device_state_service.dart';
import 'package:zapstore/services/settings_service.dart';

/// Trusted signers are persisted inside the portable device-state JSON.
class TrustedSignersService {
  const TrustedSignersService(this.ref);

  final Ref ref;

  /// Returns whether a signer pubkey is trusted by this device.
  Future<bool> isSignerTrusted(String signerPubkey) async {
    return (await ref.read(settingsServiceProvider).loadPortable())
        .trustedSigners
        .contains(signerPubkey);
  }

  /// Adds a signer pubkey to the portable trusted list.
  Future<void> addTrustedSigner(String signerPubkey) async {
    final trusted = {
      ...(await ref.read(settingsServiceProvider).loadPortable())
          .trustedSigners,
    };
    if (trusted.contains(signerPubkey)) return;
    trusted.add(signerPubkey);
    await ref
        .read(deviceStateProvider.notifier)
        .updatePortable(
          (settings) => settings.copyWith(trustedSigners: trusted),
        );
  }
}

final trustServiceProvider = Provider<TrustedSignersService>(
  (ref) => TrustedSignersService(ref),
);
