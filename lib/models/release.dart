import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/ndk.dart' as ndk;
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/user.dart';

part 'release.g.dart';

@DataRepository([NostrAdapter, ReleaseAdapter],
    fromJson: 'Release.fromMapFactory(map)', toJson: 'model.toMap()')
class Release extends BaseEvent<Release> with ndk.Release {
  Release.fromMap(super.map) : super.fromMap();

  factory Release.fromMapFactory(Map<String, dynamic> map) {
    final m = Release.fromMap(map);
    m.artifacts = HasMany<FileMetadata>.fromJson(map['artifacts']);
    return m;
  }

  late final HasMany<FileMetadata> artifacts;
}

mixin ReleaseAdapter on RemoteAdapter<Release> {
  @override
  Future<DeserializedData<Release>> deserialize(Object? data) {
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      final eTags = (map['tags'] as Iterable)
          .where((e) => e.first == 'e')
          .map((e) => e[1].toString());
      map['artifacts'] = eTags.toList();
    }
    final result = super.deserialize(data);

    return result;
  }
}

// main() async {
//   final container = ProviderContainer();
//   await container.read(repositoryInitializerProvider.future);
//   // ignore: invalid_use_of_protected_member, invalid_use_of_visible_for_testing_member
//   final fm = container
//       .read(releasesRepositoryProvider)
//       .remoteAdapter
//       .localAdapter
//       .deserialize({
//     'id': 's938j89j4f',
//     'content': 'some shit',
//     'pubkey': '78hf47hf',
//     'kind': 1063,
//     'tags': [
//       ['m', 'application/pwa+zip']
//     ]
//   });
//   print(fm.bs);
// }
