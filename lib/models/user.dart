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
    final users = <User>[];
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      if (map['kind'] == 3) {
        for (final [_, id, ..._] in map['tags'] as Iterable) {
          super.deserialize({
            'id': id,
            'content': '',
            'pubkey': id,
            'created_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
            'kind': 0,
            'tags': [],
          });
        }
      }
    }

    for (final e in list) {
      final map = e as Map<String, dynamic>;
      if (map['kind'] == 0) {
        map['id'] = map['pubkey'];
        // TODO workaround - should not assume empty is null
        if (users.isNotEmpty) {
          map['following'] = users.map((e) => e.id).toList();
        }
        final data0 = super.deserialize(map);
        final user = data0.model;
        if (user != null) {
          users.add(user);
        }
      }
    }

    return DeserializedData(users);
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
    final req =
        RelayRequest(authors: Set<String>.from(params!['ids']), kinds: {kind});
    print('in user findall $req');

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
      kinds: {kind, ...?params?.remove('kinds')},
      tags: params ?? {},
    );

    final result =
        await notifier.query(req, relayUrls: ['wss://relay.nostr.band']);
    final data = deserialize(result);
    return data.model?..saveLocal();
  }
}
