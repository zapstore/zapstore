import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/ndk.dart' as ndk;
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';

part 'file_metadata.g.dart';

@DataRepository([NostrAdapter],
    fromJson: 'FileMetadata.fromMap(map)', toJson: 'model.toMap()')
class FileMetadata extends BaseEvent<FileMetadata> with ndk.FileMetadata {
  FileMetadata.fromMap(super.map) : super.fromMap();

  late final BelongsTo<Release> release = BelongsTo();
}
