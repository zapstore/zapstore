import 'dart:convert';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';

/// Identifier used to store trusted signer preferences
const String kTrustedSignersIdentifier = 'trusted-signers';

/// Simple helper service to manage trusted signers persisted via CustomData
class TrustService {
  const TrustService(this.ref);

  final Ref ref;

  /// Returns whether a signer pubkey is trusted by the active user
  Future<bool> isSignerTrusted(String signerPubkey) async {
    final trusted = await _loadTrustedSigners();
    return trusted.contains(signerPubkey);
  }

  /// Adds a signer pubkey to the trusted list for the active user.
  /// If no signer is available (not signed in), this method is a no-op.
  Future<void> addTrustedSigner(String signerPubkey) async {
    final signer = ref.read(Signer.activeSignerProvider);
    final activePubkey = ref.read(Signer.activePubkeyProvider);
    if (signer == null || activePubkey == null) return;

    final trusted = await _loadTrustedSigners();
    if (trusted.contains(signerPubkey)) return;
    trusted.add(signerPubkey);

    final content = jsonEncode({'trusted': trusted.toList()});

    final partial = PartialCustomData(
      identifier: kTrustedSignersIdentifier,
      content: content,
    );

    // Sign with the active signer so the data is addressable under the user's pubkey
    final model = await partial.signWith(signer);

    // Save locally only (do not publish)
    await model.save();
  }

  Future<Set<String>> _loadTrustedSigners() async {
    final activePubkey = ref.read(Signer.activePubkeyProvider);
    if (activePubkey == null) return <String>{};

    try {
      final request = Request<CustomData>([
        RequestFilter<CustomData>(
          authors: {activePubkey},
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
      final map = jsonDecode(model.content) as Map<String, dynamic>;
      final list =
          (map['trusted'] as List?)?.cast<String>() ?? const <String>[];
      return list.toSet();
    } catch (_) {
      return <String>{};
    }
  }
}

final trustServiceProvider = Provider<TrustService>((ref) => TrustService(ref));
