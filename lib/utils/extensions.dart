import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/version_utils.dart';
import 'package:collection/collection.dart';

// Re-export constants for backwards compatibility
export 'package:zapstore/constants/app_constants.dart';

extension WidgetExt on WidgetRef {
  StorageNotifier get storage => read(storageNotifierProvider.notifier);
  PackageManager get packageManager => read(packageManagerProvider.notifier);
}

extension ContextExt on BuildContext {
  TextTheme get textTheme => Theme.of(this).textTheme;
}

/// Zapstore's own app identifiers
const kZapstoreAppIdentifiers = {'dev.zapstore.app', 'dev.zapstore.alpha'};

extension AppExt on App {
  /// Whether this app is one of Zapstore's own apps
  bool get isZapstoreApp => kZapstoreAppIdentifiers.contains(identifier);

  /// Whether this app is signed by Zapstore pubkey
  bool get isSignedByZapstore => pubkey == kZapstorePubkey;

  /// Whether this app is "relay signed" - indexed by Zapstore but not a Zapstore app itself
  bool get isRelaySigned => isSignedByZapstore && !isZapstoreApp;

  /// Returns PackageInfo if installed, otherwise null
  PackageInfo? get installedPackage =>
      ref.read(packageManagerProvider.notifier).getInfo(identifier);

  /// Whether the app is installed on the device
  bool get isInstalled =>
      ref.read(packageManagerProvider.notifier).isInstalled(identifier);

  /// Latest file metadata associated to the latest release
  /// Note: assumes latest metadata has been loaded for the current platform
  FileMetadata? get latestFileMetadata =>
      latestRelease.value?.latestMetadata.value;

  /// Whether there is an update available for the installed app
  /// Compares versionCode first when available, otherwise falls back to
  /// semantic version comparison.
  bool get hasUpdate {
    final installed = installedPackage;
    final latest = latestFileMetadata;
    if (installed == null || latest == null) return false;

    if (latest.versionCode != null && installed.versionCode != null) {
      return latest.versionCode! > installed.versionCode!;
    }

    return canUpgrade(installed.version, latest.version);
  }

  /// Whether the relay version would be a downgrade from the installed version
  /// Compares versionCode first when available, otherwise falls back to
  /// semantic version comparison.
  bool get hasDowngrade {
    final installed = installedPackage;
    final latest = latestFileMetadata;
    if (installed == null || latest == null) return false;

    if (latest.versionCode != null && installed.versionCode != null) {
      return latest.versionCode! < installed.versionCode!;
    }

    return canUpgrade(latest.version, installed.version);
  }

  /// Whether the installed app is up to date (installed and no update)
  bool get isUpdated => isInstalled && !hasUpdate;
}

extension AppsExt on Iterable<App> {
  Future<void> loadMetadata({bool withAuthors = true}) async {
    if (isEmpty) return;
    final ref = first.ref;

    final releases = await ref.storage.query(
      Request(
        map(
          (app) => app.latestRelease.req?.filters.firstOrNull,
        ).nonNulls.toList(),
      ),
    );

    if (releases.isEmpty) return;

    await ref.storage.query(
      Request<FileMetadata>(
        releases
            .map((r) => r.latestMetadata.req?.filters.firstOrNull)
            .nonNulls
            .toList(),
      ),
      source: const LocalAndRemoteSource(stream: false, background: true),
    );

    if (withAuthors) {
      await ref.storage.query(
        Request<Profile>(
          map(
            (a) => a.author.req?.filters.firstOrNull,
          ).nonNulls.toSet().toList(),
        ),
        source: const LocalAndRemoteSource(
          relays: 'vertex',
          stream: false,
          background: true,
        ),
      );
    }
  }
}

extension WidgetRefExt on WidgetRef {
  Ref get ref => read(Provider((ref) => ref));
}
