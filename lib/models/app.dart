import 'dart:io';

import 'package:android_package_manager/android_package_manager.dart';
import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';

part 'app.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, AppAdapter])
class App extends Event<App> with BaseApp {
  late final HasMany<Release> releases;
  late final BelongsTo<User> signer;
  late final BelongsTo<User> developer;
  String? installedVersion;
}

mixin AppAdapter on Adapter<App> {
  AndroidPackageManager? _packageManager;

  @override
  Future<List<App>> findAll(
      {bool? remote = true,
      bool? background,
      Map<String, dynamic>? params = const {},
      Map<String, String>? headers,
      bool? syncLocal,
      OnSuccessAll<App>? onSuccess,
      OnErrorAll<App>? onError,
      DataRequestLabel? label}) async {
    if (params!.containsKey('installed')) {
      final records = await getInstalledAppIdVersions();
      final ids = records.map((r) => r.$1).toSet();
      if (ids.isNotEmpty) {
        params['#d'] = ids;
        print('filtering by installed ${params['#d']}');
      }
      final apps = await super.findAll(params: params);
      for (final (id, version) in records) {
        final app = findOneLocalById(id);
        app
          ?..installedVersion = version
          ..saveLocal();
      }
      return apps;
    }
    return super.findAll(params: params);
  }

  Future<Set<(String, String)>> getInstalledAppIdVersions() async {
    late List<PackageInfo>? infos;
    if (Platform.isAndroid) {
      _packageManager ??= AndroidPackageManager();
      infos = await _packageManager!.getInstalledPackages();
    } else {
      infos = [];
    }
    final pairs = infos!
        .map((i) => (i.packageName!, i.versionName!))
        .where((r) =>
            // TODO need to filter way more
            !r.$1.startsWith('android') && !r.$1.startsWith('com.android'))
        .toSet();
    return pairs;
  }
}

extension AppX on App {
  bool get canUpdate =>
      installedVersion != null &&
      releases.latest?.androidArtifacts.firstOrNull != null &&
      releases.latest!.androidArtifacts.first.version!
              .compareTo(installedVersion!) ==
          1;

  bool get isUpdated =>
      installedVersion != null &&
      releases.latest?.androidArtifacts.firstOrNull != null &&
      releases.latest!.androidArtifacts.first.version!
              .compareTo(installedVersion!) ==
          0;
}
