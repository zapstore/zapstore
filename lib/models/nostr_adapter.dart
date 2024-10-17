import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';

// NOTE: Very important to use const in relay args to preserve equality in Riverpod families
// const kAppRelays = ['ws://10.0.2.2:3000'];
const kAppRelays = ['wss://relay.zap.store'];
const kSocialRelays = ['wss://relay.primal.net', 'wss://relay.nostr.band'];

mixin NostrAdapter<T extends DataModelMixin<T>> on Adapter<T> {
  RelayMessageNotifier get relay =>
      ref.read(relayMessageNotifierProvider(kAppRelays).notifier);
  RelayMessageNotifier get socialRelays =>
      ref.read(relayMessageNotifierProvider(kSocialRelays).notifier);

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
      map['signer'] = map['pubkey'];

      // ID should be the replaceable link/reference so as to make it replaceable in local db too
      final tagMap = tagsToMap(map['tags']);
      final isReplaceable = tagMap.containsKey('d');
      if (isReplaceable) {
        map['id'] = (
          map['kind'] as int,
          map['pubkey'].toString(),
          tagMap['d']?.firstOrNull
        ).formatted;
      }

      // Remove signature as it's already been verified
      map.remove('sig');

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
  bool existsId(Object id) {
    final r = db.select('SELECT 1 FROM _keys WHERE id = ?', [id]);
    return r.isNotEmpty;
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
    final limit = params?.remove('limit');
    final since = params?.remove('since');
    final req = RelayRequest(
      kinds: {kind, ...?additionalKinds},
      tags: params ?? {},
      limit: limit,
      since: since,
    );

    final result = await relay.queryRaw(req);
    final deserialized = await deserializeAsync(result, save: true);
    return deserialized.models;
  }
}
