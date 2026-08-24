import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/catalog_fetcher.dart';

/// Minimal [Installable] stand-in: only the fields candidate selection reads.
class _Candidate implements Installable {
  const _Candidate({
    required this.id,
    required this.versionCode,
    this.apkSignatureHash,
  });

  @override
  final String id;
  @override
  final int? versionCode;
  @override
  final String? apkSignatureHash;

  @override
  String get appIdentifier => 'com.example.wallet';
  @override
  String get hash => 'a' * 64;
  @override
  String get version => '1.0.0';
  @override
  Set<String> get urls => const {};
  @override
  Set<String> get platforms => const {'android-arm64-v8a'};
  @override
  String? get mimeType => 'application/vnd.android.package-archive';
  @override
  int? get size => 1024;
}

/// The certificate the installed app is actually signed with.
const _installedCert =
    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc';
const _attackerCert =
    'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd';

void main() {
  group('certificateTrustFor', () {
    test('a declared certificate matching the installed app is trusted', () {
      const candidate = _Candidate(
        id: 'publisher',
        versionCode: 105,
        apkSignatureHash: _installedCert,
      );

      expect(
        certificateTrustFor(candidate, const {_installedCert}),
        CandidateCertificateTrust.matching,
      );
    });

    test('a declared certificate is matched case-insensitively', () {
      final candidate = _Candidate(
        id: 'publisher',
        versionCode: 105,
        apkSignatureHash: _installedCert.toUpperCase(),
      );

      expect(
        certificateTrustFor(candidate, const {_installedCert}),
        CandidateCertificateTrust.matching,
      );
    });

    test('a candidate declaring no certificate is undeclared', () {
      const candidate = _Candidate(id: 'legacy', versionCode: 105);

      expect(
        certificateTrustFor(candidate, const {_installedCert}),
        CandidateCertificateTrust.undeclared,
      );
    });

    test('an unknown installed certificate cannot rank candidates', () {
      const candidate = _Candidate(
        id: 'publisher',
        versionCode: 105,
        apkSignatureHash: _installedCert,
      );

      expect(
        certificateTrustFor(candidate, const {}),
        CandidateCertificateTrust.undeclared,
      );
    });

    test('a declared certificate the app was not signed with mismatches', () {
      const candidate = _Candidate(
        id: 'impostor',
        versionCode: 999999,
        apkSignatureHash: _attackerCert,
      );

      expect(
        certificateTrustFor(candidate, const {_installedCert}),
        CandidateCertificateTrust.mismatching,
      );
    });
  });

  group('selectBetterInstallable', () {
    test('the publisher release wins over a higher impostor version code', () {
      const publisher = _Candidate(
        id: 'publisher',
        versionCode: 105,
        apkSignatureHash: _installedCert,
      );
      const impostor = _Candidate(id: 'impostor', versionCode: 999999);

      expect(
        selectBetterInstallable(
          publisher,
          impostor,
          const {_installedCert},
        ).id,
        'publisher',
        reason:
            'a third party must not displace the publisher release by '
            'declaring a higher version code',
      );
      expect(
        selectBetterInstallable(
          impostor,
          publisher,
          const {_installedCert},
        ).id,
        'publisher',
        reason: 'selection must not depend on the order events arrive in',
      );
    });

    test('a mismatching certificate never displaces a legacy candidate', () {
      const legacy = _Candidate(id: 'legacy', versionCode: 105);
      const impostor = _Candidate(
        id: 'impostor',
        versionCode: 999999,
        apkSignatureHash: _attackerCert,
      );

      expect(
        selectBetterInstallable(legacy, impostor, const {_installedCert}).id,
        'legacy',
        reason:
            'Android refuses an update signed by another certificate, so that '
            'candidate can never install',
      );
    });

    test('the highest version code still wins within the same trust tier', () {
      const older = _Candidate(
        id: 'older',
        versionCode: 105,
        apkSignatureHash: _installedCert,
      );
      const newer = _Candidate(
        id: 'newer',
        versionCode: 106,
        apkSignatureHash: _installedCert,
      );

      expect(
        selectBetterInstallable(older, newer, const {_installedCert}).id,
        'newer',
      );
      expect(
        selectBetterInstallable(newer, older, const {_installedCert}).id,
        'newer',
      );
    });

    test('legacy metadata keeps version code ordering when nothing is known', () {
      const older = _Candidate(id: 'older', versionCode: 105);
      const newer = _Candidate(id: 'newer', versionCode: 106);

      expect(selectBetterInstallable(older, newer, const {}).id, 'newer');
    });
  });
}
