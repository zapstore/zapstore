// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'release.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$ReleaseAdapter on Adapter<Release> {
  static final Map<String, RelationshipMeta> _kReleaseRelationshipMetas = {
    'artifacts': RelationshipMeta<FileMetadata>(
      name: 'artifacts',
      inverseName: 'release',
      type: 'fileMetadata',
      kind: 'HasMany',
      instance: (_) => (_ as Release).artifacts,
    ),
    'app': RelationshipMeta<App>(
      name: 'app',
      inverseName: 'releases',
      type: 'apps',
      kind: 'BelongsTo',
      instance: (_) => (_ as Release).app,
    ),
    'signer': RelationshipMeta<User>(
      name: 'signer',
      type: 'users',
      kind: 'BelongsTo',
      instance: (_) => (_ as Release).signer,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kReleaseRelationshipMetas;

  @override
  Release deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => _$ReleaseFromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = _$ReleaseToJson(model);
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _releasesFinders = <String, dynamic>{};

class $ReleaseAdapter = Adapter<Release>
    with _$ReleaseAdapter, NostrAdapter<Release>, ReleaseAdapter;

final releasesAdapterProvider = Provider<Adapter<Release>>(
    (ref) => $ReleaseAdapter(ref, InternalHolder(_releasesFinders)));

extension ReleaseAdapterX on Adapter<Release> {
  NostrAdapter<Release> get nostrAdapter => this as NostrAdapter<Release>;
  ReleaseAdapter get releaseAdapter => this as ReleaseAdapter;
}

extension ReleaseRelationshipGraphNodeX on RelationshipGraphNode<Release> {
  RelationshipGraphNode<FileMetadata> get artifacts {
    final meta = _$ReleaseAdapter._kReleaseRelationshipMetas['artifacts']
        as RelationshipMeta<FileMetadata>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<App> get app {
    final meta = _$ReleaseAdapter._kReleaseRelationshipMetas['app']
        as RelationshipMeta<App>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<User> get signer {
    final meta = _$ReleaseAdapter._kReleaseRelationshipMetas['signer']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

Release _$ReleaseFromJson(Map<String, dynamic> json) => Release()
  ..id = json['id']
  ..pubkey = json['pubkey'] as String
  ..createdAt = DateTime.parse(json['createdAt'] as String)
  ..content = json['content'] as String
  ..kind = (json['kind'] as num).toInt()
  ..tags = (json['tags'] as List<dynamic>)
      .map((e) => (e as List<dynamic>).map((e) => e as String).toList())
      .toList()
  ..signature = json['signature'] as String?
  ..artifacts =
      HasMany<FileMetadata>.fromJson(json['artifacts'] as Map<String, dynamic>)
  ..app = BelongsTo<App>.fromJson(json['app'] as Map<String, dynamic>)
  ..signer = BelongsTo<User>.fromJson(json['signer'] as Map<String, dynamic>);

Map<String, dynamic> _$ReleaseToJson(Release instance) => <String, dynamic>{
      'id': instance.id,
      'pubkey': instance.pubkey,
      'createdAt': instance.createdAt.toIso8601String(),
      'content': instance.content,
      'kind': instance.kind,
      'tags': instance.tags,
      'signature': instance.signature,
      'artifacts': instance.artifacts,
      'app': instance.app,
      'signer': instance.signer,
    };
