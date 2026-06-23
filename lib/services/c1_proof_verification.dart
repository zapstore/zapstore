import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';

class C1ProofVerificationPayload {
  const C1ProofVerificationPayload({
    required this.pubkey,
    required this.certificateHash,
    required this.signature,
    required this.createdAt,
    required this.expiry,
  });

  final String pubkey;
  final String certificateHash;
  final String signature;
  final DateTime createdAt;
  final DateTime expiry;

  Map<String, Object> toMap() => {
    'pubkey': pubkey,
    'certificateHash': certificateHash,
    'signature': signature,
    'createdAt': createdAt.millisecondsSinceEpoch ~/ 1000,
    'expiry': expiry.millisecondsSinceEpoch ~/ 1000,
  };
}

Future<C1ProofVerificationPayload?> c1ProofPayloadForInstallable(
  Installable target,
) async {
  final certificateHashes = target.certificateHashes;
  if (certificateHashes.isEmpty || target is! Model<dynamic>) return null;
  final model = target as Model<dynamic>;

  final List<CryptographicIdentityProof> proofs;
  try {
    proofs = await model.storage.query(
      RequestFilter<CryptographicIdentityProof>(
        tags: {'#d': certificateHashes},
        limit: 10,
      ).toRequest(),
      source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
      subscriptionPrefix: 'install-c1-${model.id}',
    );
  } catch (_) {
    return null;
  }

  final activeProofs =
      proofs
          .where((proof) => proof.isActive && proof.signature != null)
          .toList()
        ..sort((a, b) => a.createdAt.compareTo(b.createdAt));
  final proof = activeProofs.isEmpty ? null : activeProofs.last;
  final expiry = proof?.expiry;
  final signature = proof?.signature;
  if (proof == null || expiry == null || signature == null) return null;

  return C1ProofVerificationPayload(
    pubkey: proof.pubkey,
    certificateHash: proof.certificateHash,
    signature: signature,
    createdAt: proof.createdAt,
    expiry: expiry,
  );
}
