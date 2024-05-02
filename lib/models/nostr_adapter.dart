import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/user.dart';

abstract class ZapstoreEvent<T extends ZapstoreEvent<T>> = BaseEvent
    with DataModelMixin<T>;

mixin NostrAdapter<T extends ZapstoreEvent<T>> on Adapter<T> {
  late final RelayMessageNotifier notifier =
      ref.read(relayMessageNotifierProvider.notifier);

  Map<int, String> kindType = {
    0: 'users',
    3: 'users',
    1063: 'fileMetadata',
    30063: 'releases',
    32267: 'apps'
  };

  @override
  DeserializedData<T> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    final models = <T>[];
    final included = <DataModelMixin>[];

    for (final e in list) {
      final map = e as Map<String, dynamic>;
      map['createdAt'] =
          DateTime.fromMillisecondsSinceEpoch(map['created_at'] * 1000)
              .toIso8601String();
      final kind = map['kind'] as int;

      final dTags = (map['tags'] as Iterable).where((t) => t[0] == 'd');
      if (dTags.length == 1) {
        map['id'] = (dTags.first as List)[1];
      }
      map['signer'] = map['pubkey'];
      final zapTags = (map['tags'] as Iterable).where((t) => t[0] == 'zap');
      if (zapTags.length == 1) {
        map['developer'] = (zapTags.first as List)[1];
      }

      final xType = kindType[kind];
      if (xType != null) {
        if (xType == internalType) {
          final newData = super.deserialize(map);
          models.addAll(newData.models as Iterable<T>);
        } else {
          final newData = adapters[xType]!.deserialize(map);
          included.addAll(newData.models as Iterable<DataModelMixin>);
        }
      }
    }
    return DeserializedData<T>(models, included: included);
  }

  int get kind =>
      kindType.entries.firstWhere((e) => e.value == internalType).key;

  @override
  Future<List<T>> findAll(
      {bool? remote,
      bool? background,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      bool? syncLocal,
      OnSuccessAll<T>? onSuccess,
      OnErrorAll<T>? onError,
      DataRequestLabel? label}) async {
    if (remote == false) {
      return findAllLocal();
    }

    final req = RelayRequest(
      kinds: {kind, ...?params?.remove('kinds')},
      tags: params ?? {},
    );
    print(req);

    final result =
        await notifier.query(req, relayUrls: ['wss://relay.zap.store']);
    final deserialized = await deserializeAsync(result, save: true);
    return deserialized.models;
  }
}
