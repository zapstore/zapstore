// Adapted from ethicnology/dart-nostr#44 (merged), public-domain
// (CC0). TLSA-specific tests removed — they ship with the N3
// follow-up proposal (#364).

import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/namecoin/record_parser.dart';

void main() {
  group('parseHostFlat', () {
    test('bare testls.bit', () {
      final p = parseHostFlat('testls.bit')!;
      expect(p.namecoinName, 'd/testls');
      expect(p.subdomainLabels, isEmpty);
    });

    test('single-label subdomain', () {
      final p = parseHostFlat('relay.testls.bit')!;
      expect(p.namecoinName, 'd/testls');
      expect(p.subdomainLabels, ['relay']);
    });

    test('multi-label subdomain', () {
      final p = parseHostFlat('a.b.c.testls.bit')!;
      expect(p.namecoinName, 'd/testls');
      expect(p.subdomainLabels, ['a', 'b', 'c']);
    });

    test('returns null for bare .bit TLD', () {
      expect(parseHostFlat('.bit'), isNull);
      expect(parseHostFlat('bit'), isNull);
    });

    test('returns null for non-.bit hosts', () {
      expect(parseHostFlat('example.com'), isNull);
      expect(parseHostFlat('relay.example.com'), isNull);
    });

    test('is case-insensitive', () {
      final p = parseHostFlat('RELAY.TESTLS.BIT')!;
      expect(p.namecoinName, 'd/testls');
      expect(p.subdomainLabels, ['relay']);
    });

    test('handles trailing dot', () {
      final p = parseHostFlat('testls.bit.')!;
      expect(p.namecoinName, 'd/testls');
    });

    test('collapses empty middle labels', () {
      // `relay..bit` filters the empty label out and treats `relay`
      // as the registered name.
      final p = parseHostFlat('relay..bit');
      expect(p?.namecoinName, 'd/relay');
    });
  });

  group('parseRelayUrls', () {
    test('object-style nostr.relay scalar', () {
      const raw =
          '{"nostr":{"relay":"wss://relay.example.bit/"}}';
      final out = parseRelayUrls(raw);
      expect(out, ['wss://relay.example.bit/']);
    });

    test('object-style nostr.relays array', () {
      const raw =
          '{"nostr":{"relays":["wss://r1.example.bit/","wss://r2.example.bit/"]}}';
      final out = parseRelayUrls(raw);
      expect(out, containsAll(['wss://r1.example.bit/', 'wss://r2.example.bit/']));
    });

    test('top-level relays array (NIP-N2 §"top-level relays")', () {
      const raw =
          '{"relays":["wss://r1.example.bit/"],"nostr":{}}';
      final out = parseRelayUrls(raw);
      expect(out, ['wss://r1.example.bit/']);
    });

    test('walks single-label subdomain', () {
      const raw =
          '{"map":{"relay":{"nostr":{"relay":"wss://relay.example.bit/"}}}}';
      final out = parseRelayUrls(raw, ['relay']);
      expect(out, ['wss://relay.example.bit/']);
    });

    test('dedupes across object and top-level forms', () {
      const raw =
          '{"relays":["wss://r1/"],"nostr":{"relays":["wss://r1/","wss://r2/"]}}';
      final out = parseRelayUrls(raw);
      expect(out.toSet(), {'wss://r1/', 'wss://r2/'});
    });

    test('drops malformed JSON', () {
      expect(parseRelayUrls('not json'), isEmpty);
    });

    test('drops non-string entries', () {
      const raw = '{"nostr":{"relays":["wss://r1/",42,null,true]}}';
      expect(parseRelayUrls(raw), ['wss://r1/']);
    });

    test('rejects http/https URLs', () {
      const raw = '{"nostr":{"relays":["https://example.com/","wss://r1/"]}}';
      expect(parseRelayUrls(raw), ['wss://r1/']);
    });
  });

  // Tor endpoint parsing is exercised by the N2 follow-up PR
  // (#365) where it is functionally relevant — not by N1.
}
