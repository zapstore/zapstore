// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'file_metadata.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$FileMetadataAdapter on Adapter<FileMetadata> {
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
    return internalWrapStopInit(() => _$FileMetadataFromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = _$FileMetadataToJson(model);
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
  RelationshipGraphNode<User> get author {
    final meta = _$FileMetadataAdapter._kFileMetadataRelationshipMetas['author']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

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

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

FileMetadata _$FileMetadataFromJson(Map<String, dynamic> json) => FileMetadata(
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
      author: BelongsTo<User>.fromJson(json['author'] as Map<String, dynamic>),
      release:
          BelongsTo<Release>.fromJson(json['release'] as Map<String, dynamic>),
      signer: BelongsTo<User>.fromJson(json['signer'] as Map<String, dynamic>),
    );

Map<String, dynamic> _$FileMetadataToJson(FileMetadata instance) =>
    <String, dynamic>{
      'id': instance.id,
      'pubkey': instance.pubkey,
      'createdAt': instance.createdAt.toIso8601String(),
      'content': instance.content,
      'tags': instance.tags,
      'signature': instance.signature,
      'author': instance.author,
      'release': instance.release,
      'signer': instance.signer,
    };
