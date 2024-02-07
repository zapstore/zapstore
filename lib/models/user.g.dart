// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'user.dart';

// **************************************************************************
// RepositoryGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin $UserLocalAdapter on LocalAdapter<User> {
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
  User deserialize(map) {
    map = transformDeserialize(map);
    return _$UserFromJson(map);
  }

  @override
  Map<String, dynamic> serialize(model, {bool withRelationships = true}) {
    final map = _$UserToJson(model);
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _usersFinders = <String, dynamic>{};

// ignore: must_be_immutable
class $UserHiveLocalAdapter = HiveLocalAdapter<User> with $UserLocalAdapter;

class $UserRemoteAdapter = RemoteAdapter<User> with UserAdapter;

final internalUsersRemoteAdapterProvider = Provider<RemoteAdapter<User>>(
    (ref) => $UserRemoteAdapter(
        $UserHiveLocalAdapter(ref), InternalHolder(_usersFinders)));

final usersRepositoryProvider =
    Provider<Repository<User>>((ref) => Repository<User>(ref));

extension UserDataRepositoryX on Repository<User> {
  UserAdapter get userAdapter => remoteAdapter as UserAdapter;
}

extension UserRelationshipGraphNodeX on RelationshipGraphNode<User> {
  RelationshipGraphNode<User> get following {
    final meta = $UserLocalAdapter._kUserRelationshipMetas['following']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<User> get followers {
    final meta = $UserLocalAdapter._kUserRelationshipMetas['followers']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

User _$UserFromJson(Map<String, dynamic> json) => User(
      id: json['id'] as String,
      following: json['following'] == null
          ? null
          : HasMany<User>.fromJson(json['following'] as Map<String, dynamic>),
      followers: json['followers'] == null
          ? null
          : HasMany<User>.fromJson(json['followers'] as Map<String, dynamic>),
    )
      ..name = json['name'] as String?
      ..pictureUrl = json['pictureUrl'] as String?
      ..nip05 = json['nip05'] as String?;

Map<String, dynamic> _$UserToJson(User instance) => <String, dynamic>{
      'id': instance.id,
      'name': instance.name,
      'pictureUrl': instance.pictureUrl,
      'nip05': instance.nip05,
      'following': instance.following,
      'followers': instance.followers,
    };
