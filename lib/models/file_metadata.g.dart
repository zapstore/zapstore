// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'file_metadata.dart';

// **************************************************************************
// RepositoryGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin $FileMetadataLocalAdapter on LocalAdapter<FileMetadata> {
  static final Map<String, RelationshipMeta> _kFileMetadataRelationshipMetas = {
    'release': RelationshipMeta<Release>(
      name: 'release',
      inverseName: 'artifacts',
      type: 'releases',
      kind: 'BelongsTo',
      instance: (_) => (_ as FileMetadata).release,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kFileMetadataRelationshipMetas;

  @override
  FileMetadata deserialize(map) {
    map = transformDeserialize(map);
    return FileMetadata.fromMap(map);
  }

  @override
  Map<String, dynamic> serialize(model, {bool withRelationships = true}) {
    final map = model.toMap();
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _fileMetadataFinders = <String, dynamic>{};

// ignore: must_be_immutable
class $FileMetadataHiveLocalAdapter = HiveLocalAdapter<FileMetadata>
    with $FileMetadataLocalAdapter;

class $FileMetadataRemoteAdapter = RemoteAdapter<FileMetadata>
    with NostrAdapter<FileMetadata>;

final internalFileMetadataRemoteAdapterProvider =
    Provider<RemoteAdapter<FileMetadata>>((ref) => $FileMetadataRemoteAdapter(
        $FileMetadataHiveLocalAdapter(ref),
        InternalHolder(_fileMetadataFinders)));

final fileMetadataRepositoryProvider =
    Provider<Repository<FileMetadata>>((ref) => Repository<FileMetadata>(ref));

extension FileMetadataDataRepositoryX on Repository<FileMetadata> {
  NostrAdapter<FileMetadata> get nostrAdapter =>
      remoteAdapter as NostrAdapter<FileMetadata>;
}

extension FileMetadataRelationshipGraphNodeX
    on RelationshipGraphNode<FileMetadata> {
  RelationshipGraphNode<Release> get release {
    final meta =
        $FileMetadataLocalAdapter._kFileMetadataRelationshipMetas['release']
            as RelationshipMeta<Release>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}
