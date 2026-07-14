import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/unmanaged_apps_service.dart';

void main() {
  setUpAll(() {
    Model.register(
      kind: 30267,
      constructor: AppStack.fromMap,
      partialConstructor: PartialAppStack.fromMap,
    );
  });

  group('UnmanagedAppsNotifier', () {
    test('second unmanage includes the first app', () async {
      final writes = <Set<String>>[];
      final notifier = UnmanagedAppsNotifier((appIds, _) async {
        writes.add(appIds);
      });
      addTearDown(notifier.dispose);

      await notifier.toggle('app.one', unmanage: true);
      await notifier.toggle('app.two', unmanage: true);

      expect(notifier.state, {'app.one', 'app.two'});
      expect(writes, [
        {'app.one'},
        {'app.one', 'app.two'},
      ]);
    });

    test('serializes overlapping writes and keeps optimistic state', () async {
      final firstWrite = Completer<void>();
      final writes = <Set<String>>[];
      final notifier = UnmanagedAppsNotifier((appIds, _) async {
        writes.add(appIds);
        if (writes.length == 1) await firstWrite.future;
      });
      addTearDown(notifier.dispose);

      final first = notifier.toggle('app.one', unmanage: true);
      final second = notifier.toggle('app.two', unmanage: true);

      expect(notifier.state, {'app.one', 'app.two'});
      firstWrite.complete();
      await Future.wait([first, second]);

      expect(writes, [
        {'app.one'},
        {'app.one', 'app.two'},
      ]);
    });

    test('uses increasing event seconds for rapid replacements', () async {
      final timestamps = <DateTime>[];
      final now = DateTime.utc(2026, 7, 10, 20, 0, 0, 900);
      final notifier = UnmanagedAppsNotifier(
        (_, createdAt) async => timestamps.add(createdAt),
        clock: () => now,
      );
      addTearDown(notifier.dispose);

      await notifier.toggle('app.one', unmanage: true);
      await notifier.toggle('app.two', unmanage: true);

      expect(timestamps, [
        DateTime.utc(2026, 7, 10, 20),
        DateTime.utc(2026, 7, 10, 20, 0, 1),
      ]);
    });

    test('writes after a newer persisted replacement', () async {
      final timestamps = <DateTime>[];
      final now = DateTime.utc(2026, 7, 10, 20);
      final notifier = UnmanagedAppsNotifier(
        (_, createdAt) async => timestamps.add(createdAt),
        clock: () => now,
      )..acceptPersisted({'app.remote'}, now.add(const Duration(seconds: 5)));
      addTearDown(notifier.dispose);

      await notifier.toggle('app.local', unmanage: true);

      expect(timestamps, [now.add(const Duration(seconds: 6))]);
      expect(notifier.state, {'app.remote', 'app.local'});
    });

    test('rolls back optimistic state when local save fails', () async {
      final persistedAt = DateTime.utc(2026, 7, 10, 19);
      final notifier = UnmanagedAppsNotifier(
        (_, _) async => throw const UnmanagedAppsSaveException('save failed'),
      )..acceptPersisted({'app.one'}, persistedAt);
      addTearDown(notifier.dispose);

      final write = notifier.toggle('app.two', unmanage: true);
      expect(notifier.state, {'app.one', 'app.two'});

      await expectLater(write, throwsA(isA<UnmanagedAppsSaveException>()));
      expect(notifier.state, {'app.one'});
    });
  });

  test('unmanaged stack includes relay-required coordinate tags', () {
    final createdAt = DateTime.utc(2026, 7, 10, 20);
    final stack = createUnmanagedAppsStack(
      appIds: {'app.one', 'app.two'},
      platform: 'android-arm64-v8a',
      createdAt: createdAt,
    );

    expect(stack.identifier, kUnmanagedAppsIdentifier);
    expect(stack.platform, 'android-arm64-v8a');
    expect(stack.privateAppIds.toSet(), {'app.one', 'app.two'});
    expect(stack.event.createdAt, createdAt);
    expect(stack.event.containsTag('h'), isFalse);
  });

  group('wasUnmanagedStackAccepted', () {
    test(
      'uses the raw event ID for a parameterized replaceable stack',
      () async {
        final container = ProviderContainer(
          overrides: [
            storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
          ],
        );
        addTearDown(container.dispose);
        await container
            .read(storageNotifierProvider.notifier)
            .initialize(StorageConfiguration());
        final stack = createUnmanagedAppsStack(
          appIds: {'app.one'},
          platform: 'android-arm64-v8a',
          createdAt: DateTime.utc(2026, 7, 10, 20),
        ).dummySign('a' * 64);

        expect(stack.id, isNot(stack.event.id));

        final accepted = PublishResponse()
          ..addEvent(
            stack.event.id,
            relayUrl: 'wss://relay.zapstore.dev',
            accepted: true,
          );
        final rejected = PublishResponse()
          ..addEvent(
            stack.event.id,
            relayUrl: 'wss://relay.zapstore.dev',
            accepted: false,
          );
        final addressableIdResponse = PublishResponse()
          ..addEvent(
            stack.id,
            relayUrl: 'wss://relay.zapstore.dev',
            accepted: true,
          );

        expect(wasUnmanagedStackAccepted(accepted, stack), isTrue);
        expect(wasUnmanagedStackAccepted(rejected, stack), isFalse);
        expect(
          wasUnmanagedStackAccepted(addressableIdResponse, stack),
          isFalse,
        );
        expect(wasUnmanagedStackAccepted(PublishResponse(), stack), isFalse);
      },
    );
  });
}
