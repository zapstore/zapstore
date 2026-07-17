import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_sync_service.dart';
import 'package:zapstore/utils/debug_utils.dart';

void main() {
  test('boot sync is single-flight and cancellable', () async {
    final container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
      ],
    );
    addTearDown(container.dispose);
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(StorageConfiguration());
    container.read(devicePubkeyProvider.notifier).state = 'a' * 64;

    var calls = 0;
    final remote = Completer<List<Model<dynamic>>>();
    final notifier = DevicePrivateSyncNotifier(
      container.read(refProvider),
      query: (request, source, prefix) {
        calls++;
        return remote.future;
      },
    );
    addTearDown(notifier.dispose);

    final first = notifier.start();
    await notifier.start();
    expect(calls, 1);
    expect(notifier.state.phase, DevicePrivateSyncPhase.syncing);

    notifier.cancel();
    expect(notifier.state.phase, DevicePrivateSyncPhase.cancelled);
    remote.complete(const []);
    await first;

    expect(calls, 1);
    expect(notifier.state.phase, DevicePrivateSyncPhase.cancelled);
  });

  test('syncRestoredKey re-queries for the restored device pubkey', () async {
    final container = ProviderContainer(
      overrides: [
        storageNotifierProvider.overrideWith(DummyStorageNotifier.new),
      ],
    );
    await container
        .read(storageNotifierProvider.notifier)
        .initialize(StorageConfiguration());
    container.read(devicePubkeyProvider.notifier).state = 'a' * 64;

    final authors = <String>[];
    final sources = <Source>[];
    final notifier = DevicePrivateSyncNotifier(
      container.read(refProvider),
      query: (request, source, prefix) async {
        authors.add(request.filters.single.authors.single);
        sources.add(source);
        return const [];
      },
    );
    addTearDown(() {
      notifier.dispose();
      // DeviceStateNotifier.dispose reads another provider; keep this test
      // focused on sync authorship rather than container teardown order.
    });

    await notifier.start();
    expect(authors, ['a' * 64]);

    container.read(devicePubkeyProvider.notifier).state = 'b' * 64;
    await notifier.syncRestoredKey();

    expect(authors, ['a' * 64, 'b' * 64]);
    expect(sources.last, isA<LocalAndRemoteSource>());
    expect(notifier.state.phase, DevicePrivateSyncPhase.success);
  });
}
