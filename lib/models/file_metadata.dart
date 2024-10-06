import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';

part 'file_metadata.g.dart';

@DataAdapter([NostrAdapter, FileMetadataAdapter])
class FileMetadata extends BaseFileMetadata with DataModelMixin<FileMetadata> {
  final BelongsTo<User> author;
  final BelongsTo<Release> release;
  final BelongsTo<User> signer;

  FileMetadata(
      {super.createdAt,
      super.content,
      super.tags,
      required this.author,
      required this.release,
      required this.signer});

  FileMetadata.fromJson(super.map)
      : author =
            BelongsTo<User>.fromJson(map['author'] as Map<String, dynamic>),
        release =
            BelongsTo<Release>.fromJson(map['release'] as Map<String, dynamic>),
        signer =
            BelongsTo<User>.fromJson(map['signer'] as Map<String, dynamic>),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  // String? get version => tagMap['version']?.firstOrNull;
  int? get versionCode =>
      int.tryParse(tagMap['version_code']?.firstOrNull ?? '');
  String? get apkSignatureHash => tagMap['apk_signature_hash']?.firstOrNull;
}

mixin FileMetadataAdapter on Adapter<FileMetadata> {
  @override
  DeserializedData<FileMetadata> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      map['author'] = map['pubkey'];
      // Ensure tags are strings
      for (var t in map['tags']) {
        t[1] = t[1].toString();
      }
    }
    return super.deserialize(data);
  }
}
