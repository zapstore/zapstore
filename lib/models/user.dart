import 'dart:convert';

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
  final queriedAtMap = <String, DateTime>{};

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

    final request = RelayRequest(
      kinds: {0}, // 3
      authors: {...authors},
      since: queriedAtMap[authors.join()],
    );

    final result = await socialRelays.queryRaw(request);
    // Very rough caching
    queriedAtMap[authors.join()] = DateTime.now().subtract(Duration(hours: 1));

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
      kinds: {0}, // 3
      tags: params ?? {},
      authors: {publicKey},
    ));

    final data = await deserializeAsync(result, save: true);
    return data.models.firstWhere((e) {
      return e.id == publicKey;
    });
  }

  @override
  DeserializedData<User> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];

    for (final Map<String, dynamic> map in list) {
      map['id'] = map['pubkey'];
    }

    return super.deserialize(data);
  }

  Future<List<User>> getTrusted(String npub1, String npub2) async {
    final url = 'https://trustgraph.live/api/trust/$npub1/$npub2';
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

const kZapstorePubkey =
    '78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d';

const kFranzapNpub =
    'npub1wf4pufsucer5va8g9p0rj5dnhvfeh6d8w0g6eayaep5dhps6rsgs43dgh9';
