// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'app_curation_set.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$AppCurationSetAdapter on Adapter<AppCurationSet> {
  static final Map<String, RelationshipMeta> _kAppCurationSetRelationshipMetas =
      {
    'apps': RelationshipMeta<App>(
      name: 'apps',
      type: 'apps',
      kind: 'HasMany',
      instance: (_) => (_ as AppCurationSet).apps,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kAppCurationSetRelationshipMetas;

  @override
  AppCurationSet deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => _$AppCurationSetFromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = _$AppCurationSetToJson(model);
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _appCurationSetsFinders = <String, dynamic>{};

class $AppCurationSetAdapter = Adapter<AppCurationSet>
    with
        _$AppCurationSetAdapter,
        NostrAdapter<AppCurationSet>,
        AppCurationSetAdapter;

final appCurationSetsAdapterProvider = Provider<Adapter<AppCurationSet>>(
    (ref) =>
        $AppCurationSetAdapter(ref, InternalHolder(_appCurationSetsFinders)));

extension AppCurationSetAdapterX on Adapter<AppCurationSet> {
  NostrAdapter<AppCurationSet> get nostrAdapter =>
      this as NostrAdapter<AppCurationSet>;
  AppCurationSetAdapter get appCurationSetAdapter =>
      this as AppCurationSetAdapter;
}

extension AppCurationSetRelationshipGraphNodeX
    on RelationshipGraphNode<AppCurationSet> {
  RelationshipGraphNode<App> get apps {
    final meta = _$AppCurationSetAdapter
        ._kAppCurationSetRelationshipMetas['apps'] as RelationshipMeta<App>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

AppCurationSet _$AppCurationSetFromJson(Map<String, dynamic> json) =>
    AppCurationSet(
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
      apps: HasMany<App>.fromJson(json['apps'] as Map<String, dynamic>),
    );

Map<String, dynamic> _$AppCurationSetToJson(AppCurationSet instance) =>
    <String, dynamic>{
      'id': instance.id,
      'pubkey': instance.pubkey,
      'createdAt': instance.createdAt.toIso8601String(),
      'content': instance.content,
      'tags': instance.tags,
      'signature': instance.signature,
      'apps': instance.apps,
    };
