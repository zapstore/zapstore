import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/bookmarks_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';

void main() {
  test('optimistically toggles and serializes replacement writes', () async {
    final writes = <Set<String>>[];
    final gates = <Completer<void>>[];
    final notifier = BookmarksNotifier((ids, _) {
      writes.add(ids);
      final gate = Completer<void>();
      gates.add(gate);
      return gate.future;
    });
    addTearDown(notifier.dispose);

    final first = notifier.toggle('app.one');
    final second = notifier.toggle('app.two');

    expect(notifier.state, {'app.one', 'app.two'});
    await Future<void>.value();
    expect(writes, [
      {'app.one'},
    ]);

    gates.first.complete();
    await first;
    await Future<void>.value();
    expect(writes, [
      {'app.one'},
      {'app.one', 'app.two'},
    ]);

    gates.last.complete();
    await second;
  });

  test('returns whether the app was added or removed', () async {
    final notifier = BookmarksNotifier((_, _) async {});
    addTearDown(notifier.dispose);

    expect(await notifier.toggle('app.one'), isTrue);
    expect(await notifier.toggle('app.one'), isFalse);
    expect(notifier.state, isEmpty);
  });

  test('rolls back optimistic state when the local save fails', () async {
    final notifier = BookmarksNotifier((_, _) {
      throw const DevicePrivateSaveException('save failed');
    });
    addTearDown(notifier.dispose);

    await expectLater(
      notifier.toggle('app.one'),
      throwsA(isA<DevicePrivateSaveException>()),
    );
    expect(notifier.state, isEmpty);
  });
}
