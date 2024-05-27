import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';

part 'file_metadata.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, FileMetadataAdapter])
class FileMetadata extends BaseFileMetadata with DataModelMixin<FileMetadata> {
  late final BelongsTo<User> author;
  late final BelongsTo<Release> release = BelongsTo();
  late final BelongsTo<User> signer;
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
