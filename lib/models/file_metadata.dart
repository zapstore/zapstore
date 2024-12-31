import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart' as base;
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/extensions.dart';

part 'file_metadata.g.dart';

@DataAdapter([NostrAdapter, FileMetadataAdapter])
class FileMetadata extends base.FileMetadata with DataModelMixin<FileMetadata> {
  final BelongsTo<User> author;
  final BelongsTo<Release> release;
  final BelongsTo<User> signer;

  @override
  Object? get id => event.id;

  // FileMetadata(
  //     {super.createdAt,
  //     super.content,
  //     super.tags,
  //     required this.author,
  //     required this.release,
  //     required this.signer});

  FileMetadata.fromJson(super.map)
      : author = belongsTo(map['author']),
        release = belongsTo(map['release']),
        signer = belongsTo(map['signer']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  // String? get version => tagMap['version']?.firstOrNull;
  int? get versionCode => int.tryParse(event.getTag('version') ?? '');
  String? get apkSignatureHash => event.getTag('apk_signature_hash');
}

mixin FileMetadataAdapter on Adapter<FileMetadata> {
  @override
  DeserializedData<FileMetadata> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      map['author'] = map['pubkey'];
    }
    return super.deserialize(data);
  }
}
