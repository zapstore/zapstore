import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/app_catalog_relay_service.dart';

void main() {
  group('normalizeRelaySet', () {
    test('normalizes and deduplicates websocket relay URLs', () {
      expect(
        normalizeRelaySet({
          'wss://RELAY.example:443/',
          'wss://relay.example',
          'ws://localhost:80/',
        }),
        {'wss://relay.example', 'ws://localhost'},
      );
    });

    test('rejects non-websocket and malformed URLs', () {
      expect(
        normalizeRelaySet({'https://relay.example', 'not a relay', 'wss://'}),
        isEmpty,
      );
    });
  });

  group('decideRelayUpdate', () {
    final now = DateTime.utc(2026, 7, 10);

    test('offers a changed current-or-newer remote list', () {
      expect(
        decideRelayUpdate(
          currentRelays: {'wss://current.example'},
          localCreatedAt: now,
          remoteRelays: {'wss://new.example'},
          remoteCreatedAt: now,
        ),
        RelayUpdateAction.offerRemote,
      );
    });

    test('does nothing when the normalized relay set is unchanged', () {
      expect(
        decideRelayUpdate(
          currentRelays: {'wss://same.example'},
          localCreatedAt: now,
          remoteRelays: {'wss://same.example'},
          remoteCreatedAt: now.add(const Duration(minutes: 1)),
        ),
        RelayUpdateAction.none,
      );
    });

    test('publishes the accepted list when the relay copy is older', () {
      expect(
        decideRelayUpdate(
          currentRelays: {'wss://current.example'},
          localCreatedAt: now,
          remoteRelays: {'wss://old.example'},
          remoteCreatedAt: now.subtract(const Duration(minutes: 1)),
        ),
        RelayUpdateAction.publishLocal,
      );
    });

    test('keeps the hardcoded default when no event exists anywhere', () {
      expect(
        decideRelayUpdate(
          currentRelays: {'wss://relay.zapstore.dev'},
          localCreatedAt: null,
          remoteRelays: null,
          remoteCreatedAt: null,
        ),
        RelayUpdateAction.none,
      );
    });
  });
}
