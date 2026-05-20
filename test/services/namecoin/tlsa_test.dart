import 'dart:convert';
import 'dart:typed_data';

import 'package:zapstore/services/namecoin/tlsa.dart';
import 'package:pointycastle/digests/sha256.dart';
import 'package:pointycastle/digests/sha512.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('TlsaRecord.tryParse', () {
    test('valid 4-element record', () {
      final r = TlsaRecord.tryParse([3, 1, 1, 'aGVsbG8='])!;
      expect(r.usage, TlsaUsage.daneEe);
      expect(r.selector, TlsaSelector.subjectPublicKeyInfo);
      expect(r.matchingType, TlsaMatchingType.sha256);
      expect(utf8.decode(r.associationData), 'hello');
    });

    test('rejects wrong arity', () {
      expect(TlsaRecord.tryParse([3, 1, 1]), isNull);
      expect(TlsaRecord.tryParse([]), isNull);
    });

    test('rejects out-of-range codes', () {
      expect(TlsaRecord.tryParse([-1, 1, 1, 'aGVsbG8=']), isNull);
      expect(TlsaRecord.tryParse([3, -1, 1, 'aGVsbG8=']), isNull);
      expect(TlsaRecord.tryParse([3, 1, 256, 'aGVsbG8=']), isNull);
    });

    test('rejects non-string association data', () {
      expect(TlsaRecord.tryParse([3, 1, 1, 42]), isNull);
    });

    test('rejects empty association data', () {
      expect(TlsaRecord.tryParse([3, 1, 1, '']), isNull);
      expect(TlsaRecord.tryParse([3, 1, 1, '   ']), isNull);
    });

    test('unknown enum codes still parse but are usable=false', () {
      final r = TlsaRecord.tryParse([99, 99, 99, 'aGVsbG8='])!;
      expect(r.usage, TlsaUsage.unknown);
      expect(r.selector, TlsaSelector.unknown);
      expect(r.matchingType, TlsaMatchingType.unknown);
      expect(r.matchesCertificate(utf8.encode('hello')), isFalse);
    });

    test('strips whitespace inside base64', () {
      final r = TlsaRecord.tryParse([3, 1, 1, ' aGVs\nbG8 = '])!;
      expect(utf8.decode(r.associationData), 'hello');
    });

    test('tolerates unpadded base64', () {
      // "test" -> "dGVzdA==" -> stripped -> "dGVzdA"
      final r = TlsaRecord.tryParse([3, 1, 1, 'dGVzdA'])!;
      expect(utf8.decode(r.associationData), 'test');
    });

    test('tolerates url-safe alphabet', () {
      // "??" base64 standard "Pz8=", url-safe stays "Pz8="
      // Use bytes 0xfb 0xff -> standard "+/8=", url-safe "-_8="
      final standard = base64.encode([0xfb, 0xff]);
      expect(standard, '+/8=');
      final urlSafe = standard.replaceAll('+', '-').replaceAll('/', '_');
      final r = TlsaRecord.tryParse([3, 1, 1, urlSafe])!;
      expect(r.associationData, equals([0xfb, 0xff]));
    });
  });

  group('TlsaRecord matching matrix', () {
    // 32-byte deterministic "leaf cert"
    final leafCert = Uint8List.fromList(
        List<int>.generate(64, (i) => (i * 7 + 3) & 0xff));
    final leafSpki = Uint8List.fromList(
        List<int>.generate(48, (i) => (i * 11 + 5) & 0xff));

    final leafCertSha256 = SHA256Digest().process(leafCert);
    final leafSpkiSha256 = SHA256Digest().process(leafSpki);
    final leafCertSha512 = SHA512Digest().process(leafCert);
    final leafSpkiSha512 = SHA512Digest().process(leafSpki);

    String b64(List<int> b) => base64.encode(b);

    test('usage=DANE-EE selector=full-cert matchingType=exact', () {
      final r =
          TlsaRecord.tryParse([3, 0, 0, b64(leafCert)])!;
      expect(r.matchesCertificate(leafCert), isTrue);
      expect(r.matchesCertificate(leafSpki), isFalse);
    });

    test('usage=DANE-EE selector=full-cert matchingType=sha256', () {
      final r =
          TlsaRecord.tryParse([3, 0, 1, b64(leafCertSha256)])!;
      expect(r.matchesCertificate(leafCert), isTrue);
      expect(r.matchesCertificate(leafSpki), isFalse);
    });

    test('usage=DANE-EE selector=full-cert matchingType=sha512', () {
      final r =
          TlsaRecord.tryParse([3, 0, 2, b64(leafCertSha512)])!;
      expect(r.matchesCertificate(leafCert), isTrue);
    });

    test('usage=DANE-EE selector=SPKI matchingType=exact', () {
      final r =
          TlsaRecord.tryParse([3, 1, 0, b64(leafSpki)])!;
      expect(r.matchesSpki(leafSpki), isTrue);
      expect(r.matchesSpki(leafCert), isFalse);
    });

    test('usage=DANE-EE selector=SPKI matchingType=sha256', () {
      final r =
          TlsaRecord.tryParse([3, 1, 1, b64(leafSpkiSha256)])!;
      expect(r.matchesSpki(leafSpki), isTrue);
    });

    test('usage=DANE-EE selector=SPKI matchingType=sha512', () {
      final r =
          TlsaRecord.tryParse([3, 1, 2, b64(leafSpkiSha512)])!;
      expect(r.matchesSpki(leafSpki), isTrue);
    });

    test('usage=PKIX-EE requires PKIX validation', () {
      final r = TlsaRecord.tryParse([1, 1, 1, b64(leafSpkiSha256)])!;
      expect(r.requiresPkixValidation, isTrue);
      // The match itself still passes — the caller layers PKIX on top.
      expect(r.matchesSpki(leafSpki), isTrue);
    });

    test('usage=DANE-TA does NOT require PKIX validation', () {
      final r = TlsaRecord.tryParse([2, 1, 1, b64(leafSpkiSha256)])!;
      expect(r.requiresPkixValidation, isFalse);
    });

    test('usage=PKIX-TA requires PKIX validation', () {
      final r = TlsaRecord.tryParse([0, 1, 1, b64(leafSpkiSha256)])!;
      expect(r.requiresPkixValidation, isTrue);
    });

    test('mismatch: wrong hash returns false', () {
      final r = TlsaRecord.tryParse(
          [3, 1, 1, b64(SHA256Digest().process(Uint8List.fromList([1, 2, 3])))])!;
      expect(r.matchesSpki(leafSpki), isFalse);
    });
  });
}
