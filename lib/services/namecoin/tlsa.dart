import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as crypto;
import 'package:pointycastle/digests/sha512.dart';

/// RFC 6698 TLSA Certificate Usage field.
enum TlsaUsage {
  /// 0: a CA certificate that MUST appear in the certification path,
  /// AND the platform PKIX validation must succeed.
  pkixTa(0),

  /// 1: an end-entity certificate that MUST match, AND the platform
  /// PKIX validation must succeed.
  pkixEe(1),

  /// 2: a CA certificate that MUST appear in the certification path.
  /// Platform PKIX validation MAY be bypassed (DANE is the trust anchor).
  daneTa(2),

  /// 3: an end-entity certificate that MUST match. Platform PKIX
  /// validation MAY be bypassed.
  daneEe(3),

  /// Unrecognised usage code; record will be skipped.
  unknown(-1);

  /// The on-the-wire numeric code.
  final int code;

  const TlsaUsage(this.code);

  /// Returns the [TlsaUsage] matching [code], or [unknown].
  static TlsaUsage fromCode(int code) {
    for (final v in values) {
      if (v.code == code) return v;
    }
    return unknown;
  }
}

/// RFC 6698 TLSA Selector field.
enum TlsaSelector {
  /// 0: full DER-encoded certificate.
  fullCert(0),

  /// 1: SubjectPublicKeyInfo DER.
  subjectPublicKeyInfo(1),

  /// Unrecognised selector code; record will be skipped.
  unknown(-1);

  /// The on-the-wire numeric code.
  final int code;

  const TlsaSelector(this.code);

  /// Returns the [TlsaSelector] matching [code], or [unknown].
  static TlsaSelector fromCode(int code) {
    for (final v in values) {
      if (v.code == code) return v;
    }
    return unknown;
  }
}

/// RFC 6698 TLSA Matching Type field.
enum TlsaMatchingType {
  /// 0: exact match — association data is the raw bytes (full cert /
  /// SPKI).
  exact(0),

  /// 1: SHA-256 of the cert / SPKI bytes.
  sha256(1),

  /// 2: SHA-512 of the cert / SPKI bytes.
  sha512(2),

  /// Unrecognised type; record will be skipped.
  unknown(-1);

  /// The on-the-wire numeric code.
  final int code;

  const TlsaMatchingType(this.code);

  /// Returns the [TlsaMatchingType] matching [code], or [unknown].
  static TlsaMatchingType fromCode(int code) {
    for (final v in values) {
      if (v.code == code) return v;
    }
    return unknown;
  }
}

/// A single TLSA record extracted from a Namecoin `d/<name>` value's
/// `tls` field, per [RFC 6698] / [namecoin/proposals ifa-0001].
///
/// The Namecoin shape is `[usage, selector, matchingType, base64]`
/// (using base64 for the association data, not the hex form used in
/// DNS textual TLSA RRs).
///
/// [RFC 6698]: https://datatracker.ietf.org/doc/html/rfc6698
/// [namecoin/proposals ifa-0001]: https://github.com/namecoin/proposals/blob/master/ifa-0001.md
class TlsaRecord {
  /// RFC 6698 Certificate Usage.
  final TlsaUsage usage;

  /// RFC 6698 Selector.
  final TlsaSelector selector;

  /// RFC 6698 Matching Type.
  final TlsaMatchingType matchingType;

  /// Association data, decoded from the on-chain base64.
  final Uint8List associationData;

  /// Original on-chain base64 string. Useful for diagnostics; the
  /// decoded bytes are in [associationData].
  final String associationDataBase64;

  /// Creates a [TlsaRecord]. Prefer [TlsaRecord.tryParse] over the
  /// constructor when working with raw on-chain data.
  TlsaRecord({
    required this.usage,
    required this.selector,
    required this.matchingType,
    required this.associationData,
    required this.associationDataBase64,
  });

  /// Tries to parse a single TLSA record from a 4-element on-chain
  /// array. Returns `null` on any malformed shape.
  static TlsaRecord? tryParse(List<dynamic> arr) {
    if (arr.length < 4) return null;
    final u = arr[0];
    final s = arr[1];
    final m = arr[2];
    final d = arr[3];
    if (u is! int || s is! int || m is! int || d is! String) return null;
    if (u < 0 || u > 255) return null;
    if (s < 0 || s > 255) return null;
    if (m < 0 || m > 255) return null;
    final base64Str = d.trim();
    if (base64Str.isEmpty) return null;
    final decoded = _decodeAssociationData(base64Str);
    if (decoded == null) return null;
    return TlsaRecord(
      usage: TlsaUsage.fromCode(u),
      selector: TlsaSelector.fromCode(s),
      matchingType: TlsaMatchingType.fromCode(m),
      associationData: decoded,
      associationDataBase64: base64Str,
    );
  }

  /// Returns `true` if [certDer] satisfies this record's usage /
  /// selector / matching-type combination.
  ///
  /// For [TlsaSelector.subjectPublicKeyInfo] selectors, callers should
  /// use [matchesSpki] with the SPKI DER, since this method only sees
  /// the raw bytes you pass in.
  bool matchesCertificate(List<int> certDer) =>
      _match(Uint8List.fromList(certDer));

  /// Returns `true` if [spkiDer] satisfies this record's matching
  /// type. Use this when the record's [selector] is
  /// [TlsaSelector.subjectPublicKeyInfo].
  bool matchesSpki(List<int> spkiDer) =>
      _match(Uint8List.fromList(spkiDer));

  bool _match(Uint8List source) {
    if (matchingType == TlsaMatchingType.unknown) return false;
    if (usage == TlsaUsage.unknown) return false;
    if (selector == TlsaSelector.unknown) return false;
    final actual = switch (matchingType) {
      TlsaMatchingType.exact => source,
      TlsaMatchingType.sha256 =>
          Uint8List.fromList(crypto.sha256.convert(source).bytes),
      TlsaMatchingType.sha512 => SHA512Digest().process(source),
      TlsaMatchingType.unknown => Uint8List(0),
    };
    return _bytesEqual(actual, associationData);
  }

  /// `true` for `PKIX-TA` and `PKIX-EE` usages — caller MUST also run
  /// platform PKIX validation. `false` for `DANE-*` usages: the TLSA
  /// record itself is the trust anchor.
  bool get requiresPkixValidation =>
      usage == TlsaUsage.pkixTa || usage == TlsaUsage.pkixEe;

  @override
  String toString() =>
      'TlsaRecord(${usage.code}, ${selector.code}, ${matchingType.code}, $associationDataBase64)';
}

/// Strips whitespace, tolerates missing padding and the URL-safe
/// alphabet, then decodes [data] as base64. Returns null on any error.
Uint8List? _decodeAssociationData(String data) {
  final raw = data.replaceAll(RegExp(r'\s'), '');
  if (raw.isEmpty) return null;

  for (final candidate in _base64Candidates(raw)) {
    try {
      return Uint8List.fromList(base64.decode(candidate));
    } on FormatException {
      // try next variant
    }
  }
  return null;
}

Iterable<String> _base64Candidates(String raw) sync* {
  yield raw;
  // Pad to multiple of 4
  final mod = raw.length % 4;
  if (mod != 0) {
    yield raw + '=' * (4 - mod);
  }
  // URL-safe alphabet
  final urlSafe = raw.replaceAll('-', '+').replaceAll('_', '/');
  if (urlSafe != raw) {
    yield urlSafe;
    final mod2 = urlSafe.length % 4;
    if (mod2 != 0) {
      yield urlSafe + '=' * (4 - mod2);
    }
  }
}

bool _bytesEqual(Uint8List a, Uint8List b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}
