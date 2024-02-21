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
    return User.fromMapFactory(map);
  }

  @override
  Map<String, dynamic> serialize(model, {bool withRelationships = true}) {
    final map = model.toMap();
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _usersFinders = <String, dynamic>{};

// ignore: must_be_immutable
class $UserHiveLocalAdapter = HiveLocalAdapter<User> with $UserLocalAdapter;

class $UserRemoteAdapter = RemoteAdapter<User> with NostrAdapter<User>;

final internalUsersRemoteAdapterProvider = Provider<RemoteAdapter<User>>(
    (ref) => $UserRemoteAdapter(
        $UserHiveLocalAdapter(ref), InternalHolder(_usersFinders)));

final usersRepositoryProvider =
    Provider<Repository<User>>((ref) => Repository<User>(ref));

extension UserDataRepositoryX on Repository<User> {
  NostrAdapter<User> get nostrAdapter => remoteAdapter as NostrAdapter<User>;
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
