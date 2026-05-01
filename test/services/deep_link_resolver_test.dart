import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/deep_link_resolver.dart';

void main() {
  group('resolveDeepLinkPath', () {
    group('app and stack detail links', () {
      test('https zapstore.dev /apps/<id> -> /search/app/<id>', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/apps/com.foo')),
          '/search/app/com.foo',
        );
      });

      test('https zapstore.dev /stacks/<id> -> /search/stack/<id>', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/stacks/abc')),
          '/search/stack/abc',
        );
      });

      test('https zapstore.dev /stacks (no id) -> /search/stacks', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/stacks')),
          '/search/stacks',
        );
      });

      test('bare /stacks (no id) -> /search/stacks', () {
        expect(
          resolveDeepLinkPath(Uri.parse('/stacks')),
          '/search/stacks',
        );
      });

      test('bare /apps/<id> (GoRouter onException form)', () {
        expect(
          resolveDeepLinkPath(Uri.parse('/apps/com.foo')),
          '/search/app/com.foo',
        );
      });

      test('http (not https) is not a recognised deep link', () {
        expect(
          resolveDeepLinkPath(Uri.parse('http://zapstore.dev/apps/com.foo')),
          isNull,
        );
      });

      test('foreign host is not a recognised deep link', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://example.com/apps/com.foo')),
          isNull,
        );
      });

      test('empty id segment returns null', () {
        // Uri normalises trailing slash; /apps/ has one empty segment.
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/apps/')),
          isNull,
        );
      });
    });

    group('search via /apps?q=', () {
      test('https zapstore.dev /apps?q=foo -> /search?q=foo', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/apps?q=foo')),
          '/search?q=foo',
        );
      });

      test('bare /apps?q=foo -> /search?q=foo', () {
        expect(
          resolveDeepLinkPath(Uri.parse('/apps?q=foo')),
          '/search?q=foo',
        );
      });

      test('multi-word query is encoded', () {
        final result = resolveDeepLinkPath(
          Uri.parse('https://zapstore.dev/apps?q=hello+world'),
        );
        // Uri parses `+` as space; the search path should encode it back.
        expect(result, isNotNull);
        expect(
          Uri.parse(result!).queryParameters['q'],
          'hello world',
        );
      });

      test('whitespace-only q is treated as empty -> /search', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/apps?q=%20%20')),
          '/search',
        );
      });

      test('missing q -> /search (no query)', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/apps')),
          '/search',
        );
      });

      test('extra unknown query params are ignored', () {
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/apps?q=foo&utm_source=tweet'),
          ),
          '/search?q=foo',
        );
      });
    });

    group('user profile via /profile/<id>', () {
      // Real npub/hex pair from constants/app_constants.dart
      // (Zapstore community pubkey).
      const knownNpub =
          'npub14nl2afh9zsswsp5043zxe2w304afaa496gxe8z2w2rlw84ys92zqlnjx5u';
      const knownHex =
          'acfeaea6e51420e8068fac446ca9d17d7a9ef6a5d20d93894e50fee3d4902a84';

      test('valid npub is decoded to hex pubkey', () {
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/profile/$knownNpub'),
          ),
          '/search/user/$knownHex',
        );
      });

      test('bare /profile/<npub> is also decoded', () {
        expect(
          resolveDeepLinkPath(Uri.parse('/profile/$knownNpub')),
          '/search/user/$knownHex',
        );
      });

      test('64-char hex pubkey is accepted as-is', () {
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/profile/$knownHex'),
          ),
          '/search/user/$knownHex',
        );
      });

      test('uppercase hex is normalised to lowercase', () {
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/profile/${knownHex.toUpperCase()}'),
          ),
          '/search/user/$knownHex',
        );
      });

      test('malformed npub (right prefix, garbage body) returns null', () {
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/profile/npub1garbagenotreal'),
          ),
          isNull,
        );
      });

      test('non-hex non-npub string returns null', () {
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/profile/randomtext'),
          ),
          isNull,
        );
      });

      test('short hex (<64 chars) returns null', () {
        // Defensive: avoid sending truncated/partial pubkeys to UserScreen.
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/profile/deadbeef'),
          ),
          isNull,
        );
      });

      test('hex with non-hex characters returns null', () {
        // 64 chars, but contains 'z' — must be rejected.
        final invalid = 'z' * 64;
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/profile/$invalid'),
          ),
          isNull,
        );
      });

      test('empty profile id returns null', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/profile/')),
          isNull,
        );
      });
    });

    group('market:// intents', () {
      test('market://details?id=<pkg> -> /search/app/<pkg>', () {
        expect(
          resolveDeepLinkPath(Uri.parse('market://details?id=com.foo')),
          '/search/app/com.foo',
        );
      });

      test('market://search?q=foo -> /search?q=foo (not app detail)', () {
        // Regression: previously routed to /search/app/foo, which opened
        // AppDetailScreen with the search query as the app id.
        expect(
          resolveDeepLinkPath(Uri.parse('market://search?q=foo')),
          '/search?q=foo',
        );
      });

      test('market://search with empty q returns null', () {
        expect(
          resolveDeepLinkPath(Uri.parse('market://search?q=')),
          isNull,
        );
      });

      test('unknown market action returns null', () {
        expect(
          resolveDeepLinkPath(Uri.parse('market://launch?id=com.foo')),
          isNull,
        );
      });
    });

    group('unrecognised input', () {
      test('about:blank returns null', () {
        expect(resolveDeepLinkPath(Uri.parse('about:blank')), isNull);
      });

      test('zapstore.dev /about returns null', () {
        expect(
          resolveDeepLinkPath(Uri.parse('https://zapstore.dev/about')),
          isNull,
        );
      });

      test('zapstore.dev /applications/foo returns null', () {
        // Guards against the loose `pathPrefix="/apps"` footgun where
        // `/applications/...` would otherwise be interpreted as an app id.
        expect(
          resolveDeepLinkPath(
            Uri.parse('https://zapstore.dev/applications/foo'),
          ),
          isNull,
        );
      });
    });
  });
}
