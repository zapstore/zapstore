// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'settings.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$SettingsAdapter on Adapter<Settings> {
  static final Map<String, RelationshipMeta> _kSettingsRelationshipMetas = {
    'user': RelationshipMeta<User>(
      name: 'user',
      inverseName: 'settings',
      type: 'users',
      kind: 'BelongsTo',
      instance: (_) => (_ as Settings).user,
    ),
    'trustedUsers': RelationshipMeta<User>(
      name: 'trustedUsers',
      inverseName: 'settings',
      type: 'users',
      kind: 'HasMany',
      instance: (_) => (_ as Settings).trustedUsers,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kSettingsRelationshipMetas;

  @override
  Settings deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => _$SettingsFromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = _$SettingsToJson(model);
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _settingsFinders = <String, dynamic>{};

class $SettingsAdapter = Adapter<Settings>
    with _$SettingsAdapter, SettingsAdapter;

final settingsAdapterProvider = Provider<Adapter<Settings>>(
    (ref) => $SettingsAdapter(ref, InternalHolder(_settingsFinders)));

extension SettingsAdapterX on Adapter<Settings> {
  SettingsAdapter get settingsAdapter => this as SettingsAdapter;
}

extension SettingsRelationshipGraphNodeX on RelationshipGraphNode<Settings> {
  RelationshipGraphNode<User> get user {
    final meta = _$SettingsAdapter._kSettingsRelationshipMetas['user']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<User> get trustedUsers {
    final meta = _$SettingsAdapter._kSettingsRelationshipMetas['trustedUsers']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

Settings _$SettingsFromJson(Map<String, dynamic> json) => Settings()
  ..signInMethod =
      $enumDecodeNullable(_$SignInMethodEnumMap, json['signInMethod']);

Map<String, dynamic> _$SettingsToJson(Settings instance) => <String, dynamic>{
      'signInMethod': _$SignInMethodEnumMap[instance.signInMethod],
    };

const _$SignInMethodEnumMap = {
  SignInMethod.pubkey: 'pubkey',
  SignInMethod.nip55: 'nip55',
};
