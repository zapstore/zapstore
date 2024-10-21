import 'dart:async';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/utils/system_info.dart';

// NOTE: Very important to use const in relay args to preserve equality in Riverpod families
// const kAppRelays = {'ws://10.0.2.2:3000'};
const kAppRelays = {'wss://relay.zap.store'};
const kSocialRelays = {'wss://relay.damus.io', 'wss://relay.nostr.band'};

mixin NostrAdapter<T extends DataModelMixin<T>> on Adapter<T> {
  RelayMessageNotifier get relay =>
      ref.read(relayProviderFamily(kAppRelays).notifier);
  RelayMessageNotifier get socialRelays =>
      ref.read(relayProviderFamily(kSocialRelays).notifier);

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
    params ??= {};
    if (remote == false) {
      return findAllLocal();
    }

    final additionalKinds = params.remove('kinds');
    final limit = params.remove('limit');
    final since = params.remove('since');
    final until = params.remove('until');

    if (['apps', 'fileMetadatas'].contains(internalType)) {
      if (Platform.isAndroid) {
        final info = await ref.read(systemInfoProvider.future);
        params['#f'] = info.androidInfo.supportedAbis.map((a) => 'android-$a');
      }
    }

    final req = RelayRequest(
      kinds: {kind, ...?additionalKinds},
      tags: params,
      limit: limit,
      since: since,
      until: until,
    );

    final result = await relay.queryRaw(req);
    final deserialized = await deserializeAsync(result, save: true);
    return deserialized.models;
  }

  Future<void> loadArtifactsAndUsers(Iterable<Release> releases) async {
    final metadataIds =
        releases.map((r) => r.linkedEvents).nonNulls.expand((_) => _);
    final apps = releases.map((r) => r.app.value).nonNulls;

    final userIds = {
      for (final app in apps) app.signer.id,
      for (final app in apps) app.developer.id,
    }.nonNulls;

    // Metadata and users probably go to separate relays
    // so query in parallel
    await Future.wait([
      if (metadataIds.isNotEmpty)
        ref.fileMetadata.findAll(
          params: {
            'ids': metadataIds,
            '#m': [kAndroidMimeType],
          },
        ),
      if (userIds.isNotEmpty) ref.users.findAll(params: {'authors': userIds}),
    ]);
  }
}

class RelayListenerNotifier extends Notifier<void> {
  @override
  void build() {
    print('Building main listener');
    // TODO: Every 1 hour
    fetch();
    final timer = Timer.periodic(Duration(minutes: 10), (_) => fetch());

    // This will get disposed when clearing and restarting the app
    ref.onDispose(() {
      timer.cancel();
    });
  }

  Future<void> fetch() async {
    // ref.apps.appAdapter.findInstalled();
    // await ref.read(latestReleasesAppProvider.notifier).fetch();
  }
}

final relayListenerProvider =
    NotifierProvider<RelayListenerNotifier, void>(RelayListenerNotifier.new);
