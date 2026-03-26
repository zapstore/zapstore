import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';

export 'package:zapstore/constants/app_constants.dart';

extension WidgetExt on WidgetRef {
  StorageNotifier get storage => read(storageNotifierProvider.notifier);
  PackageManager get packageManager => read(packageManagerProvider.notifier);
}

extension ContextExt on BuildContext {
  TextTheme get textTheme => Theme.of(this).textTheme;
}

extension AppExt on App {
  /// Whether this app is one of Zapstore's own apps
  bool get isZapstoreApp => identifier == kZapstoreAppIdentifier;

  /// Whether this app is signed by a trusted relay pubkey
  bool get isSignedByZapstore => kTrustedRelayPubkeys.contains(pubkey);

  /// Whether this app is "relay signed" - indexed by Zapstore but not a Zapstore app itself
  bool get isRelaySigned => isSignedByZapstore && !isZapstoreApp;

  /// Returns PackageInfo if installed, otherwise null
  PackageInfo? get installedPackage =>
      ref.read(packageManagerProvider).installed[identifier];

  /// Whether the app is installed on the device
  bool get isInstalled =>
      ref.read(packageManagerProvider.notifier).isInstalled(identifier);

  /// Resolved installable for the current platform.
  /// Uses direct SoftwareAsset (3063) via `latestAsset`, or legacy
  /// FileMetadata (1063) via `latestRelease` for apps not yet on 3063.
  Installable? get installable =>
      latestAsset.value ?? latestRelease.value?.latestMetadata.value;

  /// Whether there is an update available for the installed app.
  /// Delegates to PackageManager (versionCode-only comparison).
  bool get hasUpdate {
    final latest = installable;
    if (latest == null) return false;
    return ref.read(packageManagerProvider.notifier).hasUpdate(identifier, latest);
  }

  /// Whether the relay version would be a downgrade from the installed version.
  /// Delegates to PackageManager (versionCode-only comparison).
  bool get hasDowngrade {
    final latest = installable;
    if (latest == null) return false;
    return ref.read(packageManagerProvider.notifier).hasDowngrade(identifier, latest);
  }

  /// Whether the installed app is up to date (installed and no update)
  bool get isUpdated => isInstalled && !hasUpdate;
}

extension WidgetRefExt on WidgetRef {
  Ref get asRef => read(Provider((ref) => ref));
}

/// Extension adding event-level accessors to Installable implementations.
extension InstallableExt on Installable {
  /// Creation timestamp from the underlying Nostr event.
  DateTime get createdAt => (this as Model<dynamic>).event.createdAt;


  /// Returns the primary APK certificate hash.
  /// SoftwareAsset: uses apkCertificateHashes. FileMetadata: uses apkSignatureHash.
  String? get certificateHash {
    if (this is SoftwareAsset) {
      final hashes = (this as SoftwareAsset).apkCertificateHashes;
      if (hashes.isNotEmpty) return hashes.first;
    }
    return apkSignatureHash;
  }

  /// Returns all APK certificate hashes.
  Set<String> get certificateHashes {
    if (this is SoftwareAsset) {
      final hashes = (this as SoftwareAsset).apkCertificateHashes;
      if (hashes.isNotEmpty) return hashes;
    }
    return apkSignatureHash != null ? {apkSignatureHash!} : {};
  }
}

/// String abbreviation utilities
extension StringAbbreviationExt on String {
  /// Abbreviates a string to show beginning and end with ellipsis
  /// Default: shows first 6 and last 6 chars if string is longer than 12
  String abbreviate({int start = 6, int end = 6}) {
    final t = trim();
    final minLength = start + end;
    if (t.length <= minLength) return t;
    return '${t.substring(0, start)}...${t.substring(t.length - end)}';
  }

  /// Abbreviates npub identifiers to "npub1abc...xyz" format
  /// Shows first 9 chars (npub1 + 4 chars) and last 7 chars
  /// Returns original string if not an npub identifier
  String abbreviateNpub() {
    if (!startsWith('npub1') || length < 20) {
      return this;
    }
    return '${substring(0, 9)}...${substring(length - 7)}';
  }
}
