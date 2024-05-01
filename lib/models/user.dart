import 'package:equatable/equatable.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';

part 'user.g.dart';

abstract class ZapstoreEvent<T extends ZapstoreEvent<T>> = BaseEvent
    with DataModelMixin<T>;

@JsonSerializable()
@DataAdapter([NostrAdapter, UserAdapter])
class User extends ZapstoreEvent<User> with BaseUser, EquatableMixin {
  User();
  User.fromMap(super.map) : super.fromMap();

  @DataRelationship(inverse: 'followers')
  late final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  late final HasMany<User> followers = HasMany();

  @override
  List<Object?> get props => [id];
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
          users.add(User.fromMap({
            'id': id,
            'content': '',
            'pubkey': id,
            'created_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
            'kind': 0,
            'tags': [],
            'following': {'_': null},
          }));
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
  Future<User?> findOne(Object id,
      {bool remote = true,
      bool background = false,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      OnSuccessOne<User>? onSuccess,
      OnErrorOne<User>? onError,
      DataRequestLabel? label}) async {
    String? publicKey;
    try {
      publicKey = '$id'.hexKey;
    } catch (_) {
      String username;
      String domain;
      try {
        [username, domain] = id.toString().split('@');
      } catch (_) {
        return null;
      }
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
      kinds: {kind, ...?params?.remove('kinds')},
      tags: params ?? {},
    );

    final result =
        await notifier.query(req, relayUrls: ['wss://relay.nostr.band']);
    final data = deserialize(result);
    return data.model?..saveLocal();
  }
}

mixin NostrAdapter<T extends ZapstoreEvent<T>> on Adapter<T> {
  late final RelayMessageNotifier notifier =
      ref.read(relayMessageNotifierProvider.notifier);

  Map<int, String> kindType = {
    0: 'users',
    3: 'users',
    1063: 'fileMetadata',
    30063: 'releases',
    32267: 'apps'
  };

  @override
  DeserializedData<T> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    final models = <T>[];
    final included = <DataModelMixin>[];

    for (final e in list) {
      final map = e as Map<String, dynamic>;
      map['createdAt'] =
          DateTime.fromMillisecondsSinceEpoch(map['created_at'] * 1000)
              .toIso8601String();
      final kind = map['kind'] as int;
      final xType = kindType[kind];
      if (xType != null) {
        if (xType == internalType) {
          final newData = super.deserialize(map);
          models.addAll(newData.models as Iterable<T>);
        } else {
          final newData = adapters[xType]!.deserialize(map);
          included.addAll(newData.models as Iterable<DataModelMixin>);
        }
      }
    }
    return DeserializedData<T>(models, included: included);
  }

  int get kind =>
      kindType.entries.firstWhere((e) => e.value == internalType).key;

  @override
  Future<List<T>> findAll(
      {bool? remote,
      bool? background,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      bool? syncLocal,
      OnSuccessAll<T>? onSuccess,
      OnErrorAll<T>? onError,
      DataRequestLabel? label}) async {
    if (remote == false) {
      return findAllLocal();
    }

    final req = RelayRequest(
      kinds: {kind, ...?params?.remove('kinds')},
      tags: params ?? {},
    );

    final result = await notifier.query(req);
    final deserialized = await deserializeAsync(result, save: true);
    return deserialized.models;
  }
}
