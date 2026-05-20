import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/namecoin/electrumx_client.dart';
import 'package:zapstore/services/namecoin/namecoin_relay_service.dart';

class _FakeClient implements ElectrumxClient {
  _FakeClient.value(String v)
      : _value = v,
        _exception = null;
  _FakeClient.error(Exception e)
      : _value = null,
        _exception = e;

  final String? _value;
  final Exception? _exception;

  @override
  Future<String> nameShow(String name) async {
    if (_exception != null) throw _exception;
    return _value!;
  }

  @override
  Future<void> close() async {}
}

void main() {
  group('NamecoinRelayService.isApplicable', () {
    test('returns true for wss://...bit', () {
      expect(NamecoinRelayService.isApplicable('wss://relay.example.bit'),
          isTrue);
      expect(NamecoinRelayService.isApplicable('wss://example.bit/'), isTrue);
    });

    test('returns false for DNS hostnames', () {
      expect(NamecoinRelayService.isApplicable('wss://relay.example.com'),
          isFalse);
      expect(NamecoinRelayService.isApplicable('wss://relay.zapstore.dev'),
          isFalse);
    });

    test('returns false for non-ws schemes', () {
      expect(
          NamecoinRelayService.isApplicable('https://example.bit'), isFalse);
      expect(
          NamecoinRelayService.isApplicable('namecoin:d/example'), isFalse);
    });

    test('returns false for unparseable input', () {
      expect(NamecoinRelayService.isApplicable(''), isFalse);
      expect(NamecoinRelayService.isApplicable('not a url'), isFalse);
    });
  });

  group('NamecoinRelayService.resolve', () {
    test('returns NotApplicable for DNS URLs', () async {
      final service = NamecoinRelayService.withClient(_FakeClient.value('{}'));
      final state = await service.resolve('wss://relay.example.com');
      expect(state, isA<NamecoinRelayNotApplicable>());
    });

    test('returns Resolved for record with wss endpoint', () async {
      final fake = _FakeClient.value(
        '{"nostr":{"relay":"wss://relay.example.com/"}}',
      );
      final service = NamecoinRelayService.withClient(fake);
      final state = await service.resolve('wss://example.bit/');
      expect(state, isA<NamecoinRelayResolved>());
      final r = state as NamecoinRelayResolved;
      expect(r.canonicalUrl, 'wss://example.bit/');
      expect(r.resolvedUrl, 'wss://relay.example.com/');
    });

    test('Resolved surfaces alternates and onions', () async {
      // `tor` lives at the root of the Namecoin record, alongside
      // `nostr` — not nested under it. Per record_parser.dart.
      final fake = _FakeClient.value(
        '{"nostr":{"relays":["wss://r1.example.com/","wss://r2.example.com/"]},'
        '"tor":"dhflg7a7etr77hwt4eerwoovhg7b5bivt2jem4366dt4psgnl5diyiyd.onion"}',
      );
      final service = NamecoinRelayService.withClient(fake);
      final state = await service.resolve('wss://example.bit/');
      expect(state, isA<NamecoinRelayResolved>());
      final r = state as NamecoinRelayResolved;
      // The order of the first vs alternates depends on parser
      // dedup ordering; assert both items are present total.
      expect(
        {r.resolvedUrl, ...r.alternates},
        {'wss://r1.example.com/', 'wss://r2.example.com/'},
      );
      expect(r.onionEndpoints, isNotEmpty);
    });

    test('returns Unresolved for record with no wss endpoint', () async {
      final fake = _FakeClient.value('{"nostr":{}}');
      final service = NamecoinRelayService.withClient(fake);
      final state = await service.resolve('wss://example.bit/');
      expect(state, isA<NamecoinRelayUnresolved>());
    });

    test('returns Unresolved for name-not-found', () async {
      final fake =
          _FakeClient.error(const NameNotFoundException('d/example'));
      final service = NamecoinRelayService.withClient(fake);
      final state = await service.resolve('wss://example.bit/');
      expect(state, isA<NamecoinRelayUnresolved>());
    });

    test('returns Unreachable on transport failure', () async {
      final fake = _FakeClient.error(Exception('boom'));
      final service = NamecoinRelayService.withClient(fake);
      final state = await service.resolve('wss://example.bit/');
      expect(state, isA<NamecoinRelayUnreachable>());
    });

    test('resolve never throws even with an exploding client', () async {
      final fake = _FakeClient.error(Exception('random'));
      final service = NamecoinRelayService.withClient(fake);
      final state = await service.resolve('wss://example.bit/');
      expect(state, isA<NamecoinRelayUnreachable>());
    });
  });
}
