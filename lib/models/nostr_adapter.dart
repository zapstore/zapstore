import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';

mixin NostrAdapter<T extends DataModelMixin<T>> on Adapter<T> {
  // NOTE: it is very important to use const in the argument to preserve equality
  RelayMessageNotifier get relay => ref.read(
      relayMessageNotifierProvider(const ['wss://relay.zap.store']).notifier);

  int get kind {
    return BaseEvent.kindForType(internalType)!;
  }

  @override
  DeserializedData<T> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    final models = <T>[];
    final included = <DataModelMixin>[];

    for (final e in list) {
      final map = e as Map<String, dynamic>;

      // Convert nostr-specific timestamp into a DateTime
      map['createdAt'] =
          DateTime.fromMillisecondsSinceEpoch(map['created_at'] * 1000)
              .toIso8601String();
      final kind = map['kind'] as int;

      // ID should be the identifier in PREs
      final dTags = (map['tags'] as Iterable).where((t) => t[0] == 'd');
      if (dTags.length == 1) {
        map['id'] = (dTags.first as List)[1];
      }

      // Collect models for current kind and included for others
      final eventType = BaseEvent.typeForKind(kind)!;
      if (eventType == internalType) {
        final newData = super.deserialize(map);
        models.addAll(newData.models as Iterable<T>);
      } else {
        final newData = adapters[eventType]!.deserialize(map);
        included.addAll(newData.models as Iterable<DataModelMixin>);
      }
    }
    return DeserializedData<T>(models, included: included);
  }

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

    final additionalKinds = params?.remove('kinds');
    final req = RelayRequest(
      kinds: {kind, ...?additionalKinds},
      tags: params ?? {},
    );

    final result = await relay.queryRaw(req);
    final deserialized = await deserializeAsync(result, save: true);
    return deserialized.models;
  }
}
