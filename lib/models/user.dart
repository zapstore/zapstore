import 'dart:convert';

import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/nostr_adapter.dart';

part 'user.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, UserAdapter])
class User extends ZapstoreEvent<User> with BaseUser {
  @DataRelationship(inverse: 'followers')
  late final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  late final HasMany<User> followers = HasMany();
}

mixin UserAdapter on NostrAdapter<User> {
  @override
  DeserializedData<User> deserialize(Object? data, {String? key}) {
    final Iterable<Map<String, dynamic>> list =
        (data is Iterable ? data : [data as Map]).cast();

    final k0s = list
        .where((e) => e['kind'] == 0 && jsonDecode(e['content']).isNotEmpty)
        .toList()
        .groupSetsBy((e) => e['pubkey'] as String);
    final k3s = list
        .where((e) => e['kind'] == 3)
        .toList()
        .groupSetsBy((e) => e['pubkey'] as String);

    // collect contacts and then assign them to user
    final included = <String, List<DataModelMixin>>{};
    for (final _ in k3s.entries) {
      final sl = _.value.sorted(
          (a, b) => (b['created_at'] as int).compareTo(a['created_at']));
      final k3 = sl.first;
      final contactMaps = [];
      for (final [_, id, ..._] in k3['tags'] as Iterable) {
        contactMaps.add({
          'id': id,
          'content': '',
          'pubkey': id,
          'created_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
          'kind': 0,
          'tags': [],
        });
      }
      final data = super.deserialize(contactMaps);
      included[k3['pubkey']] = data.models;
    }

    final users = <User>[];
    for (final _ in k0s.entries) {
      final sl = _.value.sorted(
          (a, b) => (b['created_at'] as int).compareTo(a['created_at']));
      if (sl.length > 1) {
        print('more than one for ${_.key}');
      }
      final k0 = sl.first;
      final id = k0['id'] = k0['pubkey'];
      if (included.containsKey(id)) {
        k0['following'] = included[id]!.map((e) => e.id).toList();
      }
      users.addAll(super.deserialize(k0).models);
    }

    return DeserializedData<User>(users,
        included: included.values.expand((_) => _).toList());
  }

  @override
  Future<List<User>> findAll(
      {bool? remote,
      bool? background,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      bool? syncLocal,
      OnSuccessAll<User>? onSuccess,
      OnErrorAll<User>? onError,
      DataRequestLabel? label}) async {
    final ids = params!['ids'];
    if (ids.isEmpty) {
      return [];
    }
    final req = RelayRequest(
      authors: Set<String>.from(ids),
      kinds: {kind, if (params['contacts'] != null) 3},
    );

    final result =
        await notifier.query(req, relayUrls: ['wss://relay.nostr.band']);
    final data = await deserializeAsync(result, save: true);
    return data.models;
  }

  @override
  Future<User?> findOne(Object id,
      {bool remote = true,
      bool background = false,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      OnSuccessOne<User>? onSuccess,
      OnErrorOne<User>? onError,
      DataRequestLabel? label}) async {
    if (id.toString().isEmpty) return null;

    var publicKey = id.toString();

    if (publicKey.startsWith('npub')) {
      publicKey = publicKey.hexKey;
    } else if (publicKey.contains('@')) {
      final [username, domain] = id.toString().split('@');
      publicKey = await sendRequest<dynamic>(
        Uri.parse('https://$domain/.well-known/nostr.json?name=$username'),
        onSuccess: (response, label) {
          return (response.body as Map)['names']?[username];
        },
      );
    }

    final req = RelayRequest(
      authors: {publicKey},
      kinds: {kind, if (params?['contacts'] != null) 3},
      tags: params ?? {},
    );

    final result =
        await notifier.query(req, relayUrls: ['wss://relay.nostr.band']);
    final data = await deserializeAsync(result, save: true);
    return data.models.firstWhere((e) {
      return e.id == publicKey;
    });
  }
}
