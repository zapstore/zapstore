import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/user.dart';

part 'release.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, ReleaseAdapter])
class Release extends ZapstoreEvent<Release> with BaseRelease {
  late final HasMany<FileMetadata> artifacts;
  late final BelongsTo<App> app;
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
      map['app'] = (adapters['apps'] as Adapter<App>)
          .findAllLocal()
          .where((a) => a.identifier == appIdentifier)
          // ignore: invalid_use_of_visible_for_testing_member
          .safeFirst
          ?.id;
    }
    return super.deserialize(data);
  }
}
