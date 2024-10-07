import 'dart:convert';

import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/utils/extensions.dart';

part 'user.g.dart';

@DataAdapter([NostrAdapter, UserAdapter])
class User extends BaseUser with DataModelMixin<User> {
  User(
      {super.createdAt,
      super.tags,
      required this.followers,
      required this.following});

  User.fromJson(super.map)
      : followers = hasMany(map['followers']),
        following = hasMany(map['following']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  @DataRelationship(inverse: 'followers')
  final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  final HasMany<User> followers;

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
    final authors = params!['authors'] as Iterable;
    if (authors.isEmpty) {
      return [];
    }

    final result = await socialRelays.queryRaw(RelayRequest(
      kinds: {0, 3},
      authors: {...authors},
    ));

    if (onSuccess != null) {
      return await onSuccess.call(DataResponse(statusCode: 200, body: result),
          label ?? DataRequestLabel('findAll', type: type), this);
    }
    final data = await deserializeAsync(result, save: true);
    return data.models;
  }

  @override
  bool isOfflineError(Object? error) {
    return false;
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
    } else {
      // If it's not an npub we treat the string as NIP-05
      if (!publicKey.contains('@')) {
        // If it does not have a @, we treat the string as a domain name
        publicKey = '_@$publicKey';
      }

      final [username, domain] = publicKey.split('@');
      publicKey = await sendRequest<dynamic>(
        Uri.parse('https://$domain/.well-known/nostr.json?name=$username'),
        onSuccess: (response, label) {
          var body = response.body;
          if (body is String) {
            body = jsonDecode(body);
          }
          return (body as Map)['names']?[username];
        },
        onError: (e, _) {
          throw e;
        },
      );
    }

    final result = await socialRelays.queryRaw(RelayRequest(
      kinds: {0, 3},
      tags: params ?? {},
      authors: {publicKey},
    ));

    final data = await deserializeAsync(result, save: true);
    return data.models.firstWhere((e) {
      return e.id == publicKey;
    });
  }

  Future<List<User>> getTrusted(String npub1, String npub2) async {
    final url = 'https://zap.store/api/trust/$npub1/$npub2';
    final users = await sendRequest(
      Uri.parse(url),
      onSuccess: (response, label) async {
        if (response.body == null) return null;
        final map =
            Map<String, dynamic>.from(jsonDecode(response.body.toString()));

        final trustedKeys = map.keys.map((npub) => npub.hexKey);
        return await findAll(
          params: {'authors': trustedKeys},
          onSuccess: (response, label, adapter) {
            final data = deserialize(response.body);
            return data.models;
          },
        );
      },
    );
    return users!;
  }
}
