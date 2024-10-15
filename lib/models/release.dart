import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/extensions.dart';

part 'release.g.dart';

@DataAdapter([NostrAdapter, ReleaseAdapter])
class Release extends BaseRelease with DataModelMixin<Release> {
  final HasMany<FileMetadata> artifacts;
  final BelongsTo<App> app;
  final BelongsTo<User> signer;

  Release(
      {super.createdAt,
      super.content,
      super.tags,
      required this.artifacts,
      required this.app,
      required this.signer});

  Release.fromJson(super.map)
      : app = belongsTo(map['app']),
        artifacts = hasMany(map['artifacts']),
        signer = belongsTo(map['signer']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();
}

mixin ReleaseAdapter on Adapter<Release> {
  @override
  DeserializedData<Release> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];

    for (final Map<String, dynamic> map in list) {
      final tagMap = tagsToMap(map['tags']);
      map['app'] = tagMap['a']?.firstOrNull;
      map['artifacts'] = tagMap['e']!.toList();
    }
    return super.deserialize(data);
  }
}

extension ReleaseExt on Iterable<Release> {
  List<Release> get sortedByLatest =>
      sorted((a, b) => b.createdAt!.compareTo(a.createdAt!));
}
