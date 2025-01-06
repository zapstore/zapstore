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
    ),
    'settings': RelationshipMeta<Settings>(
      name: 'settings',
      inverseName: 'user',
      type: 'settings',
      kind: 'BelongsTo',
      instance: (_) => (_ as User).settings,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kUserRelationshipMetas;

  @override
  User deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => User.fromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = model.toJson();
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

  RelationshipGraphNode<Settings> get settings {
    final meta = _$UserAdapter._kUserRelationshipMetas['settings']
        as RelationshipMeta<Settings>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}
