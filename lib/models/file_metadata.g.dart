// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'file_metadata.dart';

// **************************************************************************
// RepositoryGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin $FileMetadataLocalAdapter on LocalAdapter<FileMetadata> {
  static final Map<String, RelationshipMeta> _kFileMetadataRelationshipMetas = {
    'author': RelationshipMeta<User>(
      name: 'author',
      type: 'users',
      kind: 'BelongsTo',
      instance: (_) => (_ as FileMetadata).author,
    ),
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
    return FileMetadata.fromMapFactory(map);
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
    with NostrAdapter<FileMetadata>, FileMetadataAdapter;

final internalFileMetadataRemoteAdapterProvider =
    Provider<RemoteAdapter<FileMetadata>>((ref) => $FileMetadataRemoteAdapter(
        $FileMetadataHiveLocalAdapter(ref),
        InternalHolder(_fileMetadataFinders)));

final fileMetadataRepositoryProvider =
    Provider<Repository<FileMetadata>>((ref) => Repository<FileMetadata>(ref));

extension FileMetadataDataRepositoryX on Repository<FileMetadata> {
  NostrAdapter<FileMetadata> get nostrAdapter =>
      remoteAdapter as NostrAdapter<FileMetadata>;
  FileMetadataAdapter get fileMetadataAdapter =>
      remoteAdapter as FileMetadataAdapter;
}

extension FileMetadataRelationshipGraphNodeX
    on RelationshipGraphNode<FileMetadata> {
  RelationshipGraphNode<User> get author {
    final meta = $FileMetadataLocalAdapter
        ._kFileMetadataRelationshipMetas['author'] as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<Release> get release {
    final meta =
        $FileMetadataLocalAdapter._kFileMetadataRelationshipMetas['release']
            as RelationshipMeta<Release>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}
