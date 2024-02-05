// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'metadata.dart';

// **************************************************************************
// RepositoryGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin $FileMetadataLocalAdapter on LocalAdapter<FileMetadata> {
  static final Map<String, RelationshipMeta> _kFileMetadataRelationshipMetas =
      {};

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kFileMetadataRelationshipMetas;

  @override
  FileMetadata deserialize(map) {
    map = transformDeserialize(map);
    return _$FileMetadataFromJson(map);
  }

  @override
  Map<String, dynamic> serialize(model, {bool withRelationships = true}) {
    final map = _$FileMetadataToJson(model);
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
    on RelationshipGraphNode<FileMetadata> {}

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

FileMetadata _$FileMetadataFromJson(Map<String, dynamic> json) => FileMetadata(
      id: json['id'] as String,
      pubkey: json['pubkey'] as String,
      createdAt: DateTime.parse(json['created_at'] as String),
      content: json['content'] as String,
      sig: json['sig'] as String,
      tags: (json['tags'] as List<dynamic>?)
              ?.map(
                  (e) => (e as List<dynamic>).map((e) => e as String).toList())
              .toList() ??
          const [],
    );

Map<String, dynamic> _$FileMetadataToJson(FileMetadata instance) =>
    <String, dynamic>{
      'id': instance.id,
      'pubkey': instance.pubkey,
      'content': instance.content,
      'sig': instance.sig,
      'created_at': instance.createdAt.toIso8601String(),
      'tags': instance.tags,
    };
