import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/utils/image_url.dart';

void main() {
  group('getCdnImageUrl', () {
    test('adds the requested class to Zapstore CDN URLs', () {
      expect(
        getCdnImageUrl('https://cdn.zapstore.dev/file.png', CdnImageVariant.icon),
        'https://cdn.zapstore.dev/file.png?class=icon',
      );
      expect(
        getCdnImageUrl(
          'https://cdn.zapstore.dev/file.png',
          CdnImageVariant.iconsm,
        ),
        'https://cdn.zapstore.dev/file.png?class=iconsm',
      );
    });

    test('preserves existing query parameters', () {
      expect(
        getCdnImageUrl(
          'https://cdn.zapstore.dev/file.png?x=1',
          CdnImageVariant.thumbsm,
        ),
        'https://cdn.zapstore.dev/file.png?x=1&class=thumbsm',
      );
    });

    test('does not transform other hosts or invalid URLs', () {
      expect(
        getCdnImageUrl('https://example.com/file.png', CdnImageVariant.icon),
        'https://example.com/file.png',
      );
      expect(
        getCdnImageUrl('/file.png', CdnImageVariant.icon),
        '/file.png',
      );
    });

    test('returns null and empty unchanged', () {
      expect(getCdnImageUrl(null, CdnImageVariant.icon), isNull);
      expect(getCdnImageUrl('', CdnImageVariant.icon), '');
    });
  });
}
