// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'user.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$UserAdapter on Adapter<User> {
  static final Map<String, RelationshipMeta> _kUserRelationshipMetas = {
    'following': RelationshipMeta<User>(
      name: 'following',
      inverseName: 'followers',
      type: 'users',
      kind: 'HasMany',
      instance: (_) => (_ as User).following,
    ),
    'followers': RelationshipMeta<User>(
      name: 'followers',
      inverseName: 'following',
      type: 'users',
      kind: 'HasMany',
      instance: (_) => (_ as User).followers,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kUserRelationshipMetas;

  @override
  User deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => _$UserFromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = _$UserToJson(model);
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _usersFinders = <String, dynamic>{};

class $UserAdapter = Adapter<User>
    with _$UserAdapter, NostrAdapter<User>, UserAdapter;

final usersAdapterProvider = Provider<Adapter<User>>(
    (ref) => $UserAdapter(ref, InternalHolder(_usersFinders)));

extension UserAdapterX on Adapter<User> {
  NostrAdapter<User> get nostrAdapter => this as NostrAdapter<User>;
  UserAdapter get userAdapter => this as UserAdapter;
}

extension UserRelationshipGraphNodeX on RelationshipGraphNode<User> {
  RelationshipGraphNode<User> get following {
    final meta = _$UserAdapter._kUserRelationshipMetas['following']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<User> get followers {
    final meta = _$UserAdapter._kUserRelationshipMetas['followers']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

User _$UserFromJson(Map<String, dynamic> json) => User(
      id: json['id'],
      pubkey: json['pubkey'] as String?,
      createdAt: json['createdAt'] == null
          ? null
          : DateTime.parse(json['createdAt'] as String),
      content: json['content'] as String? ?? '',
      tags: (json['tags'] as List<dynamic>?)
              ?.map(
                  (e) => (e as List<dynamic>).map((e) => e as String).toList())
              .toList() ??
          const [],
      signature: json['signature'] as String?,
      followers:
          HasMany<User>.fromJson(json['followers'] as Map<String, dynamic>),
      following:
          HasMany<User>.fromJson(json['following'] as Map<String, dynamic>),
    );

Map<String, dynamic> _$UserToJson(User instance) => <String, dynamic>{
      'id': instance.id,
      'pubkey': instance.pubkey,
      'createdAt': instance.createdAt.toIso8601String(),
      'content': instance.content,
      'tags': instance.tags,
      'signature': instance.signature,
      'following': instance.following,
      'followers': instance.followers,
    };
