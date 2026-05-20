import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/namecoin/electrumx_client.dart';
import 'package:zapstore/services/namecoin/namecoin_tlsa_service.dart';

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
  group('NamecoinTlsaService.isApplicable', () {
    test('returns true for .bit hostnames', () {
      expect(NamecoinTlsaService.isApplicable('relay.example.bit'), isTrue);
      expect(NamecoinTlsaService.isApplicable('example.bit'), isTrue);
    });

    test('returns false for DNS hostnames', () {
      expect(
        NamecoinTlsaService.isApplicable('relay.example.com'),
        isFalse,
      );
    });

    test('returns false for empty/garbage', () {
      expect(NamecoinTlsaService.isApplicable(''), isFalse);
      expect(NamecoinTlsaService.isApplicable('   '), isFalse);
    });
  });

  group('NamecoinTlsaService.fetchPins', () {
    test('returns NotApplicable for DNS hostnames', () async {
      final service = NamecoinTlsaService.withClient(_FakeClient.value('{}'));
      final state = await service.fetchPins('relay.example.com');
      expect(state, isA<NamecoinTlsaNotApplicable>());
    });

    test('returns PinsAvailable when record carries tls entries', () async {
      const json = '{"tls":[[3,1,1,"'
          'a05e6b1f49a02fa68c41ee72c70d3b6f9a4f7a55a17a2c9f5b7f0d3e1c2b4a6d'
          '"]]}';
      final fake = _FakeClient.value(json);
      final service = NamecoinTlsaService.withClient(fake);
      final state = await service.fetchPins('example.bit');
      expect(state, isA<NamecoinTlsaPinsAvailable>());
      final p = state as NamecoinTlsaPinsAvailable;
      expect(p.pins.length, 1);
      expect(p.namecoinName, 'd/example');
    });

    test('returns NoPins when record has no tls field', () async {
      final fake = _FakeClient.value('{"nostr":{}}');
      final service = NamecoinTlsaService.withClient(fake);
      final state = await service.fetchPins('example.bit');
      expect(state, isA<NamecoinTlsaNoPins>());
    });

    test('returns Unknown for name-not-found', () async {
      final fake =
          _FakeClient.error(const NameNotFoundException('d/example'));
      final service = NamecoinTlsaService.withClient(fake);
      final state = await service.fetchPins('example.bit');
      expect(state, isA<NamecoinTlsaUnknown>());
    });

    test('returns Unreachable on transport failure', () async {
      final fake = _FakeClient.error(Exception('boom'));
      final service = NamecoinTlsaService.withClient(fake);
      final state = await service.fetchPins('example.bit');
      expect(state, isA<NamecoinTlsaUnreachable>());
    });

    test('fetchPins never throws even with exploding client', () async {
      final fake = _FakeClient.error(Exception('random'));
      final service = NamecoinTlsaService.withClient(fake);
      final state = await service.fetchPins('example.bit');
      expect(state, isA<NamecoinTlsaUnreachable>());
    });

    test('walks subdomain label', () async {
      const json = '{"map":{"relay":{"tls":[[3,1,1,"deadbeef"]]}}}';
      final fake = _FakeClient.value(json);
      final service = NamecoinTlsaService.withClient(fake);
      final state = await service.fetchPins('relay.example.bit');
      expect(state, isA<NamecoinTlsaPinsAvailable>());
      final p = state as NamecoinTlsaPinsAvailable;
      expect(p.pins.length, 1);
    });
  });
}
