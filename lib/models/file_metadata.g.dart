// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'file_metadata.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$FileMetadataAdapter on Adapter<FileMetadata> {
  static final Map<String, RelationshipMeta> _kFileMetadataRelationshipMetas = {
    'release': RelationshipMeta<Release>(
      name: 'release',
      inverseName: 'artifacts',
      type: 'releases',
      kind: 'BelongsTo',
      instance: (_) => (_ as FileMetadata).release,
    ),
    'signer': RelationshipMeta<User>(
      name: 'signer',
      type: 'users',
      kind: 'BelongsTo',
      instance: (_) => (_ as FileMetadata).signer,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kFileMetadataRelationshipMetas;

  @override
  FileMetadata deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => FileMetadata.fromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = model.toJson();
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _fileMetadataFinders = <String, dynamic>{};

class $FileMetadataAdapter = Adapter<FileMetadata>
    with _$FileMetadataAdapter, NostrAdapter<FileMetadata>, FileMetadataAdapter;

final fileMetadataAdapterProvider = Provider<Adapter<FileMetadata>>(
    (ref) => $FileMetadataAdapter(ref, InternalHolder(_fileMetadataFinders)));

extension FileMetadataAdapterX on Adapter<FileMetadata> {
  NostrAdapter<FileMetadata> get nostrAdapter =>
      this as NostrAdapter<FileMetadata>;
  FileMetadataAdapter get fileMetadataAdapter => this as FileMetadataAdapter;
}

extension FileMetadataRelationshipGraphNodeX
    on RelationshipGraphNode<FileMetadata> {
  RelationshipGraphNode<Release> get release {
    final meta =
        _$FileMetadataAdapter._kFileMetadataRelationshipMetas['release']
            as RelationshipMeta<Release>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<User> get signer {
    final meta = _$FileMetadataAdapter._kFileMetadataRelationshipMetas['signer']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}
