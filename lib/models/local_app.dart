import 'dart:io';

import 'package:android_package_manager/android_package_manager.dart';
import 'package:collection/collection.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';

part 'local_app.g.dart';

@DataAdapter([LocalAppAdapter])
@JsonSerializable()
class LocalApp extends DataModel<LocalApp> {
  @override
  final Object? id;
  final String? installedVersion;
  final int? installedVersionCode;
  final AppInstallStatus? status;

  LocalApp(
      {required this.id,
      this.installedVersion,
      this.installedVersionCode,
      this.status});

  LocalApp copyWith(
      {String? installedVersion,
      int? installedVersionCode,
      AppInstallStatus? status}) {
    return LocalApp(
      id: id,
      installedVersion: installedVersion ?? this.installedVersion,
      installedVersionCode: installedVersionCode ?? this.installedVersionCode,
      status: status ?? this.status,
    );
  }
}

mixin LocalAppAdapter on Adapter<LocalApp> {
  Future<void> refreshUpdateStatus({String? appId}) async {
    if (!Platform.isAndroid) {
      return;
    }
    // NOTE: Using packageManager.getPackageInfo(packageName: appId)
    // throws an uncatchable error every time it queries a non-installed package
    final infos = await packageManager.getInstalledPackages(
        flags: PackageInfoFlags(
      {PMFlag.getPermissions, PMFlag.getSigningCertificates},
    ));

    final installedPackageInfos = infos!.where((i) =>
        !kExcludedAppIdNamespaces.any((e) => i.packageName!.startsWith(e)));

    final ids = installedPackageInfos.map((i) => i.packageName).nonNulls;

    // appId == null is the case of all apps
    if (appId == null) {
      final localApps = findAllLocal();
      final apps = ref.apps.appAdapter.findWhereIdentifierInLocal(ids);

      final localAppsToSave = <LocalApp>[];

      for (final i in installedPackageInfos) {
        final appId = i.packageName!;
        final app = apps.firstWhereOrNull((a) => a.identifier == appId);

        final installedVersion = i.versionName;
        final installedVersionCode = i.versionCode;
        final signingCertificateBytes =
            i.signingInfo?.signingCertificateHistory?.firstOrNull;

        final status = determineUpdateStatus(app, installedVersion,
            installedVersionCode, signingCertificateBytes);

        if (status == null) {
          continue;
        }
        final localApp = localApps.firstWhereOrNull((app) => appId == app.id) ??
            LocalApp(id: i.packageName!);
        localAppsToSave.add(
          localApp.copyWith(
            installedVersion: installedVersion,
            installedVersionCode: installedVersionCode,
            status: status,
          ),
        );
      }

      // Save local apps
      await saveManyLocal(localAppsToSave, async: true);

      // Remove uninstalled apps
      final uninstalledApps = localApps.where((a) => !ids.contains(a.id));
      if (uninstalledApps.isNotEmpty) {
        deleteLocalByKeys(uninstalledApps.map((a) => DataModel.keyFor(a)));
      }
    } else {
      final app =
          ref.apps.appAdapter.findWhereIdentifierInLocal({appId}).firstOrNull;
      final i =
          installedPackageInfos.firstWhereOrNull((i) => i.packageName == appId);

      final installedVersion = i?.versionName;
      final installedVersionCode = i?.versionCode;
      final signingCertificateBytes =
          i?.signingInfo?.signingCertificateHistory?.firstOrNull;

      final status = determineUpdateStatus(
          app, installedVersion, installedVersionCode, signingCertificateBytes);

      if (status != null) {
        final localApp = app!.localApp.value ?? LocalApp(id: i!.packageName!);

        localApp
            .copyWith(
              installedVersion: installedVersion,
              installedVersionCode: installedVersionCode,
              status: status,
            )
            .saveLocal();
      }
    }

    updateNumberOfApps();
  }

  void updateNumberOfApps() {
    final rs = db.select(
        'SELECT count(*) as c FROM localApps WHERE json_extract(data, \'\$.status\') is \'updatable\'');
    ref.read(appsToUpdateProvider.notifier).state = rs.first['c'];
  }

  AppInstallStatus? determineUpdateStatus(App? app, String? installedVersion,
      int? installedVersionCode, List<int>? signingCertificateBytes) {
    if (app == null || app.releases.isEmpty || app.latestMetadata == null) {
      return null;
    }
    if (signingCertificateBytes != null) {
      final installedApkSigHash =
          sha256.convert(signingCertificateBytes).toString().toLowerCase();
      final metadataSigHashes =
          app.latestMetadata?.event.getTagSet('apk_signature_hash') ?? {};
      final matches = metadataSigHashes
          .any((msh) => msh.toLowerCase() == installedApkSigHash);
      if (!matches) {
        return AppInstallStatus.certificateMismatch;
      }
    }

    if (installedVersion == null || installedVersionCode == null) {
      return null;
    }

    var comp = 0;
    if (app.latestMetadata!.versionCode != null) {
      // NOTE: Zapstore development versions always carry a lower version code
      // (e.g. 12) than published ones (e.g. 2012)
      final code = app.identifier == kZapstoreAppIdentifier && !kReleaseMode
          ? installedVersionCode + 2000
          : installedVersionCode;
      comp = app.latestMetadata!.versionCode!.compareTo(code);
    }

    if (comp == 0) {
      comp = app.latestMetadata!.version!.compareTo(installedVersion);
    }

    if (comp == 1) return AppInstallStatus.updatable;
    if (comp == 0) return AppInstallStatus.updated;
    // else it's a downgrade, which is not installable
    return AppInstallStatus.downgrade;
  }
}

enum AppInstallStatus {
  updated,
  updatable,
  downgrade,
  certificateMismatch,
}
