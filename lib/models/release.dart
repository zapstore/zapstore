import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/user.dart';

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
      : app = BelongsTo<App>.fromJson(map['app'] as Map<String, dynamic>),
        artifacts = HasMany<FileMetadata>.fromJson(
            map['artifacts'] as Map<String, dynamic>),
        signer =
            BelongsTo<User>.fromJson(map['signer'] as Map<String, dynamic>),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();
}

mixin ReleaseAdapter on Adapter<Release> {
  @override
  DeserializedData<Release> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      final eTags = (map['tags'] as Iterable)
          .where((e) => e.first == 'e')
          .map((e) => e[1].toString());
      map['artifacts'] = eTags.toList();
      final appIdentifier =
          map['tags'].firstWhere((t) => t.first == 'd')[1].split('@').first;
      map['app'] = appIdentifier;
    }
    return super.deserialize(data);
  }
}

extension HasManyReleaseX on HasMany<Release> {
  List<Release> get ordered =>
      toList().sorted((a, b) => b.createdAt!.compareTo(a.createdAt!));
}
