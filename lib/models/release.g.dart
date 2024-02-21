// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'release.dart';

// **************************************************************************
// RepositoryGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin $ReleaseLocalAdapter on LocalAdapter<Release> {
  static final Map<String, RelationshipMeta> _kReleaseRelationshipMetas = {
    'artifacts': RelationshipMeta<FileMetadata>(
      name: 'artifacts',
      inverseName: 'release',
      type: 'fileMetadata',
      kind: 'HasMany',
      instance: (_) => (_ as Release).artifacts,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kReleaseRelationshipMetas;

  @override
  Release deserialize(map) {
    map = transformDeserialize(map);
    return Release.fromMapFactory(map);
  }

  @override
  Map<String, dynamic> serialize(model, {bool withRelationships = true}) {
    final map = model.toMap();
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _releasesFinders = <String, dynamic>{};

// ignore: must_be_immutable
class $ReleaseHiveLocalAdapter = HiveLocalAdapter<Release>
    with $ReleaseLocalAdapter;

class $ReleaseRemoteAdapter = RemoteAdapter<Release>
    with NostrAdapter<Release>, ReleaseAdapter;

final internalReleasesRemoteAdapterProvider = Provider<RemoteAdapter<Release>>(
    (ref) => $ReleaseRemoteAdapter(
        $ReleaseHiveLocalAdapter(ref), InternalHolder(_releasesFinders)));

final releasesRepositoryProvider =
    Provider<Repository<Release>>((ref) => Repository<Release>(ref));

extension ReleaseDataRepositoryX on Repository<Release> {
  NostrAdapter<Release> get nostrAdapter =>
      remoteAdapter as NostrAdapter<Release>;
  ReleaseAdapter get releaseAdapter => remoteAdapter as ReleaseAdapter;
}

extension ReleaseRelationshipGraphNodeX on RelationshipGraphNode<Release> {
  RelationshipGraphNode<FileMetadata> get artifacts {
    final meta = $ReleaseLocalAdapter._kReleaseRelationshipMetas['artifacts']
        as RelationshipMeta<FileMetadata>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}
