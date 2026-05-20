import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/namecoin/electrumx_client.dart';
import 'package:zapstore/services/namecoin/namecoin_nip05_service.dart';

class _FakeClient implements ElectrumxClient {
  _FakeClient.value(this._value)
      : _exception = null;
  _FakeClient.error(Exception ex)
      : _value = null,
        _exception = ex;

  final String? _value;
  final Exception? _exception;
  int callCount = 0;

  @override
  Future<String> nameShow(String name) async {
    callCount++;
    if (_exception != null) throw _exception;
    return _value!;
  }

  @override
  Future<void> close() async {}
}

void main() {
  group('NamecoinNip05Service.isApplicable', () {
    test('returns true for .bit identifiers', () {
      expect(NamecoinNip05Service.isApplicable('testls.bit'), isTrue);
      expect(NamecoinNip05Service.isApplicable('alice@testls.bit'), isTrue);
      expect(NamecoinNip05Service.isApplicable('d/testls'), isTrue);
      expect(NamecoinNip05Service.isApplicable('id/alice'), isTrue);
    });

    test('returns false for DNS identifiers', () {
      expect(NamecoinNip05Service.isApplicable('alice@example.com'), isFalse);
      expect(NamecoinNip05Service.isApplicable('example.com'), isFalse);
    });

    test('returns false for null/empty', () {
      expect(NamecoinNip05Service.isApplicable(null), isFalse);
      expect(NamecoinNip05Service.isApplicable(''), isFalse);
    });

    test('tolerates nostr: URI prefix', () {
      expect(NamecoinNip05Service.isApplicable('nostr:alice@testls.bit'), isTrue);
    });
  });

  group('NamecoinNip05Service.verify', () {
    const validPubkey =
        '6cdebccabda1dfa058ab85352a79509b592b2bdfa0370325e28ec1cb4f18667d';
    const otherPubkey =
        '460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c';

    test('returns NotApplicable for non-.bit identifier', () async {
      final service = NamecoinNip05Service.withClient(
        _FakeClient.value('unused'),
      );
      final state = await service.verify(
        identifier: 'alice@example.com',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05NotApplicable>());
    });

    test('returns Verified when pubkey matches (root _ form)', () async {
      final fake = _FakeClient.value(
        '{"nostr":{"names":{"_":"$validPubkey"}}}',
      );
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Verified>());
      final v = state as NamecoinNip05Verified;
      expect(v.namecoinName, 'd/testls');
      expect(v.localPart, '_');
      expect(v.pubkey, validPubkey);
      expect(fake.callCount, 1);
    });

    test('returns Verified for localpart@name.bit', () async {
      final fake = _FakeClient.value(
        '{"nostr":{"names":{"alice":"$validPubkey","_":"$otherPubkey"}}}',
      );
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'alice@testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Verified>());
      final v = state as NamecoinNip05Verified;
      expect(v.localPart, 'alice');
      expect(v.pubkey, validPubkey);
    });

    test('returns Mismatch when on-chain pubkey differs', () async {
      final fake = _FakeClient.value(
        '{"nostr":{"names":{"_":"$otherPubkey"}}}',
      );
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Mismatch>());
      final m = state as NamecoinNip05Mismatch;
      expect(m.onChainPubkey, otherPubkey);
      expect(m.claimedPubkey, validPubkey);
    });

    test('returns Unverified for missing nostr field', () async {
      final fake = _FakeClient.value('{}');
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Unverified>());
    });

    test('returns Unverified for name-not-found', () async {
      final fake = _FakeClient.error(
        const NameNotFoundException('d/testls'),
      );
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Unverified>());
      expect(
        (state as NamecoinNip05Unverified).reason,
        contains('not registered'),
      );
    });

    test('returns Unverified for expired name', () async {
      final fake = _FakeClient.error(
        const NameExpiredException('d/testls'),
      );
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Unverified>());
      expect(
        (state as NamecoinNip05Unverified).reason,
        contains('expired'),
      );
    });

    test('returns Unreachable for transport failure', () async {
      final fake = _FakeClient.error(
        const ElectrumxUnreachableException('boom'),
      );
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Unreachable>());
    });

    test('pubkey comparison is case-insensitive', () async {
      final fake = _FakeClient.value(
        '{"nostr":{"names":{"_":"${validPubkey.toUpperCase()}"}}}',
      );
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Verified>());
    });

    test('verify never throws even with an exploding client', () async {
      final fake = _FakeClient.error(Exception('random transport error'));
      final service = NamecoinNip05Service.withClient(fake);
      final state = await service.verify(
        identifier: 'testls.bit',
        claimedPubkey: validPubkey,
      );
      expect(state, isA<NamecoinNip05Unreachable>());
    });
  });
}
