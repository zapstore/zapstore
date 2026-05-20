import 'package:zapstore/services/attestations/attestation_models.dart';

/// Parses raw `kind:38383` event tags + content into a
/// [ServiceAttestation], or returns `null` if the event is
/// structurally invalid (missing `p`/`d` tags, or `p` tag value
/// is not a 64-hex pubkey).
///
/// Per NIP-N4: tolerant of unknown / future tags — only the named
/// recognised tags are read. This matches INVARIANTS.md "Parsing
/// unknown, missing, or future tags must not crash the app."
ServiceAttestation? parseServiceAttestation({
  required String attesterPubkey,
  required int createdAtSec,
  required List<List<String>> tags,
  required String content,
}) {
  String? attesteePubkey;
  String? dTag;
  String? serviceCategory;
  double? ratingScore;
  double? ratingMax;
  NamecoinAttestationAnchor? anchor;
  int? completedAtSec;

  for (final tag in tags) {
    if (tag.isEmpty) continue;
    switch (tag[0]) {
      case 'p':
        if (tag.length >= 2 && _isHex32(tag[1])) {
          attesteePubkey = tag[1].toLowerCase();
        }
      case 'd':
        if (tag.length >= 2) dTag = tag[1];
      case 'service':
        if (tag.length >= 2) serviceCategory = tag[1];
      case 'rating':
        if (tag.length >= 3) {
          ratingScore = double.tryParse(tag[1]);
          ratingMax = double.tryParse(tag[2]);
        }
      case 'completed_at':
        if (tag.length >= 2) {
          completedAtSec = int.tryParse(tag[1]);
        }
      case 'nmc':
        if (tag.length >= 3) {
          final h = int.tryParse(tag[2]);
          if (h != null && h >= 0 && tag[1].isNotEmpty) {
            anchor = NamecoinAttestationAnchor(
              name: tag[1],
              blockHeight: h,
            );
          }
        }
    }
  }

  if (attesteePubkey == null) return null;
  if (dTag == null) return null;
  if (!_isHex32(attesterPubkey)) return null;
  if (createdAtSec <= 0) return null;

  double? normalised;
  if (ratingScore != null &&
      ratingMax != null &&
      ratingScore >= 0 &&
      ratingMax > 0) {
    final n = ratingScore / ratingMax;
    if (n.isFinite) {
      normalised = n.clamp(0.0, 1.0);
    }
  }

  final createdAt = DateTime.fromMillisecondsSinceEpoch(
    createdAtSec * 1000,
    isUtc: true,
  );
  final completedAt = completedAtSec != null && completedAtSec > 0
      ? DateTime.fromMillisecondsSinceEpoch(
          completedAtSec * 1000,
          isUtc: true,
        )
      : createdAt;

  return ServiceAttestation(
    attesterPubkey: attesterPubkey.toLowerCase(),
    attesteePubkey: attesteePubkey,
    dTag: dTag,
    serviceCategory: serviceCategory,
    normalisedRating: normalised,
    ratingScore: ratingScore,
    ratingMax: ratingMax,
    namecoinAnchor: anchor,
    content: content,
    createdAt: createdAt,
    completedAt: completedAt,
  );
}

/// Collapses a list of attestations to the most recent per
/// `(attesterPubkey, dTag)` pair. Newest `createdAt` wins.
List<ServiceAttestation> dedupeAttestations(
  Iterable<ServiceAttestation> input,
) {
  final keyed = <String, ServiceAttestation>{};
  for (final att in input) {
    final key = '${att.attesterPubkey}:${att.dTag}';
    final existing = keyed[key];
    if (existing == null ||
        att.createdAt.isAfter(existing.createdAt)) {
      keyed[key] = att;
    }
  }
  final out = keyed.values.toList()
    ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
  return out;
}

bool _isHex32(String s) {
  if (s.length != 64) return false;
  for (final c in s.codeUnits) {
    final isDigit = c >= 0x30 && c <= 0x39;
    final isLowerHex = c >= 0x61 && c <= 0x66;
    final isUpperHex = c >= 0x41 && c <= 0x46;
    if (!isDigit && !isLowerHex && !isUpperHex) return false;
  }
  return true;
}
