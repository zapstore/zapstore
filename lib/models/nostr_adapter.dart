import 'dart:async';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart' hide Release;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/widgets/latest_releases_container.dart';

// NOTE: Very important to use const in relay args to preserve equality in Riverpod families
// const kAppRelays = {'ws://10.0.2.2:3000'};
const kAppRelays = {'wss://relay.zapstore.dev'};
const kSocialRelays = {'wss://relay.damus.io', 'wss://relay.primal.net', 'ndk'};
const kVertexRelay = {'wss://relay.vertexlab.io'};

mixin NostrAdapter<T extends DataModelMixin<T>> on Adapter<T> {
  RelayMessageNotifier get relay =>
      ref.read(relayProviderFamily(kAppRelays).notifier);

  RelayMessageNotifier get socialRelays =>
      ref.read(relayProviderFamily(kSocialRelays).notifier);

  RelayMessageNotifier get vertexRelay =>
      ref.read(relayProviderFamily(kVertexRelay).notifier);

  @override
  Future<void> onInitialized() async {
    await super.onInitialized();

    // DO NOT run on isolates, the mere fact of calling the
    // relay getter will trigger its initialization
    // as providers are obviously not cached across isolates
    if (!inIsolate) {
      // Upon adapter initialization, configure relays
      // with the event verification caching function
      relay.configure(
        isEventVerified: (Map<String, dynamic> map) {
          // If replaceable, we check for that ID
          final identifier = (map['tags'] as Iterable)
              .firstWhereOrNull((t) => t[0] == 'd')?[1]
              ?.toString();
          final id = identifier != null
              ? (map['kind'] as int, map['pubkey'].toString(), identifier)
                  .formatted
              : map['id'];
          return ref.apps.nostrAdapter.existsId(id);
        },
      );
      socialRelays.configure(
        isEventVerified: (map) => ref.apps.nostrAdapter.existsId(map['pubkey']),
      );
    }
  }

  int get kind {
    var pbType = internalType.singularize().capitalize();
    pbType =
        pbType.replaceFirst('datum', 'data'); // handle FileMetadata edge case
    return Event.types[pbType]!.kind;
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
      final tags = map['tags'];
      final isReplaceable = BaseUtil.containsTag(tags, 'd');
      if (isReplaceable) {
        map['id'] = (
          map['kind'] as int,
          map['pubkey'].toString(),
          BaseUtil.getTag(tags, 'd')!,
        ).formatted;
      }

      // Remove signature as it's already been verified
      map.remove('sig');

      // Collect models for current kind and included for others
      final eventType = Event.types.entries
          .firstWhere((e) => e.value.kind == kind)
          .key
          .pluralize()
          .decapitalize();
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

  Iterable<String> existingIds(Iterable<Object> ids) {
    final r = db.select(
        'SELECT id FROM _keys WHERE id in (${ids.map((e) => '?').join(',')})',
        ids.toList());
    return r.map((e) => e['id']);
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
    final ignoreReturn = params.remove('ignoreReturn');

    if (['apps', 'fileMetadatas'].contains(internalType)) {
      if (Platform.isAndroid) {
        final info =
            await ref.read(systemInfoNotifierProvider.notifier).fetch();
        params['#f'] = info.androidInfo.supportedAbis.map((a) => 'android-$a');
      }
    }

    final req = RelayRequest(
      kinds: {kind, ...?additionalKinds},
      tags: params,
      limit: limit,
      until: until,
      // Disable since when until is present
      since: until != null ? null : since,
    );

    final result = await relay.queryRaw(req);
    final deserialized = await deserializeAsync(result,
        save: true, ignoreReturn: ignoreReturn == true);
    return deserialized.models;
  }

  Future<void> loadArtifactsAndUsers(Iterable<Release> releases) async {
    final metadataIds =
        releases.map((r) => r.event.linkedEvents).nonNulls.expand((_) => _);
    final apps = releases.map((r) => r.app.value).nonNulls;

    final userIds = {
      for (final app in apps) app.signer.id,
      for (final app in apps) app.developer.id,
    }.nonNulls;

    // Metadata and users probably go to separate relays
    // so query in parallel
    // Can use ignoreReturn on both as we only care about saved models
    await Future.wait([
      if (metadataIds.isNotEmpty)
        ref.fileMetadata.findAll(
          params: {
            'ids': metadataIds,
            '#m': [kAndroidMimeType],
            'ignoreReturn': true,
          },
        ),
      if (userIds.isNotEmpty)
        ref.users.findAll(
          params: {
            'authors': userIds,
            'ignoreReturn': true,
          },
        ),
    ]);
  }
}

class RelayListenerNotifier extends Notifier<void> {
  @override
  void build() {
    final timer = Timer.periodic(Duration(minutes: 30), (_) => fetch());

    // This will get disposed when clearing and restarting the app
    ref.onDispose(() {
      timer.cancel();
    });
  }

  Future<void> fetch() async {
    await Future.microtask(() async {
      await ref.read(latestReleasesAppProvider.notifier).fetchRemote();
      await ref.apps.appAdapter.checkForUpdates();
    });
  }
}

final relayListenerProvider =
    NotifierProvider<RelayListenerNotifier, void>(RelayListenerNotifier.new);
