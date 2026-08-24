import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/download_source.dart';

const _hash =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

/// Minimal [Installable] stand-in: only the fields download resolution reads.
class _Target implements Installable {
  const _Target({this.hash = _hash, this.urls = const {}});

  @override
  final String hash;
  @override
  final Set<String> urls;

  @override
  String get id => 'target';
  @override
  String get appIdentifier => 'com.example.wallet';
  @override
  String get version => '1.0.0';
  @override
  int? get versionCode => 100;
  @override
  Set<String> get platforms => const {'android-arm64-v8a'};
  @override
  String? get mimeType => 'application/vnd.android.package-archive';
  @override
  int? get size => 1024;
  @override
  String? get apkSignatureHash => null;
}

void main() {
  group('isValidApkHash', () {
    test('accepts a 64 character hex digest in either case', () {
      expect(isValidApkHash(_hash), isTrue);
      expect(isValidApkHash(_hash.toUpperCase()), isTrue);
    });

    test('rejects anything that cannot be a SHA-256 digest', () {
      expect(isValidApkHash(null), isFalse);
      expect(isValidApkHash(''), isFalse);
      expect(isValidApkHash('a' * 63), isFalse);
      expect(isValidApkHash('a' * 65), isFalse);
      expect(isValidApkHash('z' * 64), isFalse);
      expect(isValidApkHash('  $_hash  '), isFalse);
    });
  });

  group('resolveDownloadUrl', () {
    test('uses an https CDN url from metadata verbatim', () {
      const target = _Target(urls: {'https://cdn.zapstore.dev/$_hash'});

      expect(resolveDownloadUrl(target), 'https://cdn.zapstore.dev/$_hash');
    });

    test('does not follow a cleartext url, even on the CDN host', () {
      const target = _Target(urls: {'http://cdn.zapstore.dev/$_hash'});

      expect(
        resolveDownloadUrl(target),
        'https://cdn.zapstore.dev/$_hash?redirect=true',
        reason:
            'metadata must not be able to downgrade the download to cleartext',
      );
    });

    test('routes a third-party host through the CDN by content hash', () {
      const target = _Target(urls: {'https://github.com/x/releases/app.apk'});

      expect(
        resolveDownloadUrl(target),
        'https://cdn.zapstore.dev/$_hash?redirect=true',
      );
    });

    test('is not fooled by the CDN host in the userinfo component', () {
      const target = _Target(urls: {'https://cdn.zapstore.dev@evil.tld/x.apk'});

      expect(
        resolveDownloadUrl(target),
        'https://cdn.zapstore.dev/$_hash?redirect=true',
      );
    });

    test('refuses to build a CDN url from a malformed hash', () {
      const target = _Target(
        hash: 'not-a-sha256',
        urls: {'https://github.com/x/releases/app.apk'},
      );

      expect(
        resolveDownloadUrl(target),
        isNull,
        reason:
            'the hash is what the native installer verifies against; a '
            'malformed one must stop the operation, not become part of a URL',
      );
    });

    test('returns null when metadata carries no url', () {
      const target = _Target();

      expect(resolveDownloadUrl(target), isNull);
    });
  });
}
