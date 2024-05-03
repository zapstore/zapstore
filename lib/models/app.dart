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
class App extends ZapstoreEvent<App> with BaseApp {
  late final HasMany<Release> releases;
  late final BelongsTo<User> signer;
  late final BelongsTo<User> developer;
  String? currentVersion;
}

mixin AppAdapter on Adapter<App> {
  final _packageManager = AndroidPackageManager();

  Future<Set<(String, String)>> getInstalledAppIdVersions() async {
    final infos = await _packageManager.getInstalledPackages();
    final pairs = infos!
        .map((i) => (i.packageName!, i.versionName!))
        .where((r) =>
            !r.$1.startsWith('android') && !r.$1.startsWith('com.android'))
        .toSet();
    return pairs;
  }

  Future<String?> getInstalledAppVersion(String id) async {
    final infos = await _packageManager.getInstalledPackages();
    return infos!.firstWhereOrNull((i) => i.packageName == id)?.versionName;
  }

  Future<Set<App>> getInstalledApps({String? only}) async {
    final pairs = await getInstalledAppIdVersions();
    final ids =
        pairs.map((p) => p.$1).where((id) => only != null ? id == only : true);
    if (ids.isEmpty) {
      return {};
    }
    final apps = await findAll(params: {'#d': ids});
    for (final (id, version) in pairs) {
      apps.firstWhereOrNull((app) => app.id == id)?.currentVersion = version;
    }
    return apps.where((app) => app.releases.isNotEmpty).toSet();
  }
}

extension AppX on App {
  // NOTE: we MUST call getInstalledApps() to use currentVersion
  bool get canUpdate =>
      currentVersion != null &&
      releases.latest != null &&
      releases.latest!.version.compareTo(currentVersion!) == 1;

  bool get isUpdated =>
      currentVersion != null &&
      releases.latest != null &&
      releases.latest!.version.compareTo(currentVersion!) == 0;
}
