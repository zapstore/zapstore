/// Pre-release script that generates assets/seed.db for new-user cold start.
///
/// Fetches curated stacks (kind 30267) and their referenced apps (kind 32267)
/// from the AppCatalog relay, plus author profiles (kind 0) from social relays.
/// Writes them into a SQLite database using the same schema/codec as purplebase.
///
/// Usage:
///   dart run tool/seed_database.dart
///
/// The output file is assets/seed.db — commit it before building the release APK.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:sqlite3/sqlite3.dart';
import 'package:web_socket/web_socket.dart';

// Internal purplebase imports for codec + schema
import 'package:purplebase/src/db/codec.dart';
import 'package:purplebase/src/db/schema.dart';

const _kZapstoreCommunityPubkey =
    'acfeaea6e51420e8068fac446ca9d17d7a9ef6a5d20d93894e50fee3d4902a84';

const _kAppCatalogRelay = 'wss://relay.zapstore.dev';

const _kProfileRelays = [
  'wss://relay.vertexlab.io',
  'wss://relay.damus.io',
  'wss://relay.primal.net',
  'wss://nos.lol',
];

void main() async {
  final outputPath = 'assets/seed.db';

  stdout.writeln('=== Zapstore seed database generator ===\n');

  // 1. Fetch stacks
  stdout.writeln('Fetching stacks from $_kAppCatalogRelay ...');
  final stacks = await _fetchEvents(
    _kAppCatalogRelay,
    {'kinds': [30267], 'authors': [_kZapstoreCommunityPubkey], 'limit': 100},
  );
  stdout.writeln('  ${stacks.length} stacks');

  // 2. Extract referenced app coordinates and author pubkeys
  final appRefs = <String>{};
  final pubkeys = <String>{_kZapstoreCommunityPubkey};

  for (final stack in stacks) {
    pubkeys.add(stack['pubkey'] as String);
    for (final tag in (stack['tags'] as List)) {
      if (tag[0] == 'a' && (tag[1] as String).startsWith('32267:')) {
        appRefs.add(tag[1] as String);
        pubkeys.add((tag[1] as String).split(':')[1]);
      }
    }
  }

  // 3. Fetch apps by d-tag
  final dTags = appRefs.map((r) => r.split(':')[2]).toList();
  stdout.writeln('Fetching ${dTags.length} apps from $_kAppCatalogRelay ...');
  final apps = await _fetchEvents(
    _kAppCatalogRelay,
    {'kinds': [32267], '#d': dTags, 'limit': 500},
  );
  stdout.writeln('  ${apps.length} apps');

  // Collect any additional pubkeys from fetched apps
  for (final app in apps) {
    pubkeys.add(app['pubkey'] as String);
  }

  // 4. Fetch profiles from multiple relays, dedup by pubkey (keep newest)
  stdout.writeln('Fetching profiles for ${pubkeys.length} pubkeys ...');
  final profilesByPubkey = <String, Map<String, dynamic>>{};

  for (final relay in [_kAppCatalogRelay, ..._kProfileRelays]) {
    final remaining =
        pubkeys.where((pk) => !profilesByPubkey.containsKey(pk)).toList();
    if (remaining.isEmpty) break;

    stdout.write('  $relay (${remaining.length} remaining) ... ');
    try {
      final profiles = await _fetchEvents(
        relay,
        {'kinds': [0], 'authors': remaining, 'limit': remaining.length},
      );
      for (final p in profiles) {
        final pk = p['pubkey'] as String;
        final existing = profilesByPubkey[pk];
        if (existing == null ||
            (p['created_at'] as int) > (existing['created_at'] as int)) {
          profilesByPubkey[pk] = p;
        }
      }
      stdout.writeln('${profiles.length} found');
    } catch (e) {
      stdout.writeln('failed ($e)');
    }
  }

  final profiles = profilesByPubkey.values.toList();
  final missingCount = pubkeys.length - profiles.length;
  stdout.writeln('  ${profiles.length} profiles total'
      '${missingCount > 0 ? ' ($missingCount missing — will be fetched at runtime)' : ''}');

  // 5. Build the SQLite database
  final allEvents = [...stacks, ...apps, ...profiles];
  stdout.writeln('\nWriting ${allEvents.length} events to $outputPath ...');

  final dbFile = File(outputPath);
  if (dbFile.existsSync()) dbFile.deleteSync();
  dbFile.parent.createSync(recursive: true);

  final db = sqlite3.open(outputPath);
  try {
    db.execute(setUpSql);
    _insertEvents(db, allEvents);
    db.execute('VACUUM');
  } finally {
    db.dispose();
  }

  final fileSize = File(outputPath).lengthSync();
  stdout.writeln('  Done: $fileSize bytes (${(fileSize / 1024).toStringAsFixed(1)} KB)');
  stdout.writeln('\nSeed database ready. Commit $outputPath before building the release.');
}

/// Fetch events from a single relay using a one-shot REQ/EOSE pattern.
Future<List<Map<String, dynamic>>> _fetchEvents(
  String relayUrl,
  Map<String, dynamic> filter,
) async {
  final uri = Uri.parse(relayUrl);
  final ws = await WebSocket.connect(uri).timeout(const Duration(seconds: 10));

  final events = <Map<String, dynamic>>[];
  final completer = Completer<List<Map<String, dynamic>>>();
  const subId = 'seed';

  final sub = ws.events.listen((event) {
    if (event case TextDataReceived(:final text)) {
      final msg = jsonDecode(text) as List;
      if (msg[0] == 'EVENT' && msg[1] == subId) {
        events.add(msg[2] as Map<String, dynamic>);
      } else if (msg[0] == 'EOSE' && msg[1] == subId) {
        if (!completer.isCompleted) completer.complete(events);
      }
    }
  });

  ws.sendText(jsonEncode(['REQ', subId, filter]));

  try {
    return await completer.future.timeout(const Duration(seconds: 15));
  } finally {
    ws.sendText(jsonEncode(['CLOSE', subId]));
    sub.cancel();
    unawaited(ws.close().catchError((_) {}));
  }
}

/// Insert events into the database using the same codec as purplebase.
void _insertEvents(Database db, List<Map<String, dynamic>> events) {
  final (encodedEvents, tagsForId) = EventCodec.encode(events);

  final sql = '''
    INSERT INTO events (id, pubkey, kind, created_at, blob)
    VALUES (:id, :pubkey, :kind, :created_at, :blob)
    ON CONFLICT(id) DO UPDATE SET
        pubkey = EXCLUDED.pubkey,
        kind = EXCLUDED.kind,
        created_at = EXCLUDED.created_at,
        blob = EXCLUDED.blob
    WHERE EXCLUDED.created_at > events.created_at;
    INSERT OR REPLACE INTO event_tags (event_id, value, is_relay)
    VALUES (:event_id, :value, :is_relay);
  ''';

  final [eventPs, tagsPs] = db.prepareMultiple(sql);

  try {
    db.execute('BEGIN');
    for (final event in encodedEvents) {
      eventPs.executeWith(StatementParameters.named(event));

      for (final List tag in tagsForId[event[':id']]!) {
        if (tag.length < 2 || tag[0].toString().length > 1) continue;
        tagsPs.executeWith(
          StatementParameters.named({
            ':event_id': event[':id'],
            ':value': '${tag[0]}:${tag[1]}',
            ':is_relay': false,
          }),
        );
      }
    }
    db.execute('COMMIT');
  } catch (e) {
    db.execute('ROLLBACK');
    rethrow;
  } finally {
    eventPs.dispose();
    tagsPs.dispose();
  }
}
