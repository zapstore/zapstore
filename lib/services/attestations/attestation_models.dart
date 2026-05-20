/// Plain-Dart representation of a parsed `kind:38383` service
/// attestation (NIP-N4 — see https://github.com/mstrofnone/nips/blob/master/N4.md).
///
/// Kept as a separate type from `Model` to keep this experimental
/// surface decoupled from the `models` package — N4 has no stable
/// upstream kind allocation yet, and shipping it inside `models`
/// would force a coordinated release across packages.
class ServiceAttestation {
  /// Hex pubkey of the attester (the event's `pubkey`).
  final String attesterPubkey;

  /// Hex pubkey of the attestee (the event's `p` tag value).
  final String attesteePubkey;

  /// `d` tag value — the attester's unique identifier for this
  /// attestation (lets them re-rate later).
  final String dTag;

  /// `service` tag's primary category (e.g. `software.android-app`).
  final String? serviceCategory;

  /// Normalised rating in [0, 1]. Computed from
  /// `rating[0] / rating[1]` with both clamped to non-negative.
  /// `null` when the rating tag is missing or unparseable.
  final double? normalisedRating;

  /// Raw rating numerator (`rating[0]`).
  final double? ratingScore;

  /// Raw rating denominator (`rating[1]`).
  final double? ratingMax;

  /// Optional Namecoin anchor (`nmc` tag) — name + block height.
  /// `null` when absent.
  final NamecoinAttestationAnchor? namecoinAnchor;

  /// Optional free-text content. May be empty.
  final String content;

  /// `created_at` of the event.
  final DateTime createdAt;

  /// `completed_at` tag value, or [createdAt] if absent.
  final DateTime completedAt;

  const ServiceAttestation({
    required this.attesterPubkey,
    required this.attesteePubkey,
    required this.dTag,
    required this.serviceCategory,
    required this.normalisedRating,
    required this.ratingScore,
    required this.ratingMax,
    required this.namecoinAnchor,
    required this.content,
    required this.createdAt,
    required this.completedAt,
  });
}

/// `nmc` tag — Namecoin identity anchor for an attestation.
class NamecoinAttestationAnchor {
  /// Full Namecoin name (`d/example`, `id/alice`, or `<name>.bit`).
  final String name;

  /// Block height at which the attestee's pubkey was active under
  /// `nostr.names._` (or `.<localpart>`).
  final int blockHeight;

  const NamecoinAttestationAnchor({
    required this.name,
    required this.blockHeight,
  });
}

/// A summary across multiple attestations about the same attestee.
///
/// **Experimental**: kind:38383 is draft-only in the NIP track and
/// has no production producer yet. UI surfaces consuming this
/// summary MUST label it experimental and MUST NOT influence
/// whitelisting, blocking, or install decisions.
class AttestationSummary {
  /// The pubkey being attested about.
  final String attesteePubkey;

  /// Most-recent attestation per attester (deduplicated by
  /// `(attester, d-tag)` — newest `created_at` wins).
  final List<ServiceAttestation> attestations;

  const AttestationSummary({
    required this.attesteePubkey,
    required this.attestations,
  });

  /// Number of distinct attester pubkeys.
  int get attesterCount =>
      attestations.map((a) => a.attesterPubkey).toSet().length;

  /// Total number of attestations. Equal to [attesterCount] when
  /// every attester contributed at most one attestation.
  int get attestationCount => attestations.length;

  /// Arithmetic mean of [ServiceAttestation.normalisedRating] across
  /// attestations that have a parseable rating. Returns `null` when
  /// no attestation has a rating.
  double? get averageRating {
    final rated = attestations
        .map((a) => a.normalisedRating)
        .whereType<double>()
        .toList(growable: false);
    if (rated.isEmpty) return null;
    final sum = rated.fold<double>(0, (a, b) => a + b);
    return sum / rated.length;
  }

  /// Number of attestations carrying a Namecoin identity anchor.
  int get namecoinAnchoredCount =>
      attestations.where((a) => a.namecoinAnchor != null).length;
}
