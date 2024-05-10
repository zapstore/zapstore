import 'dart:convert';

import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/nostr_adapter.dart';

part 'user.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, UserAdapter])
class User extends Event<User> with BaseUser {
  @DataRelationship(inverse: 'followers')
  late final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  late final HasMany<User> followers = HasMany();

  String get nameOrNpub => name ?? '${npub.substring(0, 10)}...';
}

mixin UserAdapter on NostrAdapter<User> {
  @override
  DeserializedData<User> deserialize(Object? data, {String? key}) {
    final Iterable<Map<String, dynamic>> list =
        (data is Iterable ? data : [data as Map]).cast();

    final k0s = list
        .where((e) {
          // filter shitty kind 0s
          if (e['kind'] != 0 || !e['content'].toString().startsWith('{')) {
            return false;
          }
          final map = Map<String, dynamic>.from(jsonDecode(e['content']));
          final name = map['name'] ?? map['display_name'] ?? map['displayName'];
          return name != null;
        })
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
        if (!existsId(id)) {
          contactMaps.add({
            'id': id,
            'content': '',
            'pubkey': id,
            'created_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
            'kind': 0,
            'tags': [],
          });
        }
      }
      final data = super.deserialize(contactMaps);
      included[k3['pubkey']] = data.models;
    }

    final users = <User>[];
    for (final _ in k0s.entries) {
      final sl = _.value.sorted(
          (a, b) => (b['created_at'] as int).compareTo(a['created_at']));
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
    final authors = params!['authors'];
    if (authors.isEmpty) {
      return [];
    }
    final req = RelayRequest(
      authors: Set<String>.from(authors),
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

    String? publicKey = id.toString();

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

    if (publicKey == null) {
      return null;
    }

    final req = RelayRequest(
      authors: {publicKey},
      kinds: {kind, if (params?['contacts'] != null) 3},
      tags: params ?? {},
    );

    // trigger trust service indexing in the background
    // sendRequest<dynamic>(
    //     Uri.parse('https://zap.store/api/trust/${publicKey.npub}/r'));

    final result =
        await notifier.query(req, relayUrls: ['wss://relay.nostr.band']);
    final data = await deserializeAsync(result, save: true);
    return data.models.firstWhere((e) {
      return e.id == publicKey;
    });
  }

  Future<List<User>> getTrusted(User u1, User u2) async {
    final url = 'https://zap.store/api/trust/${u1.npub}/${u2.npub}';
    final users = await sendRequest(
      Uri.parse(url),
      onSuccess: (response, label) async {
        if (response.body == null) return null;
        final map =
            Map<String, dynamic>.from(jsonDecode(response.body.toString()));

        final trustedKeys = map.keys.map((npub) => npub.hexKey);
        return await findAll(params: {'authors': trustedKeys});
      },
    );
    return users!;
  }
}
