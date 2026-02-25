import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';

/// Provider that exposes the backup function with correct Ref.
final backupInstalledAppsProvider = Provider<Future<int> Function()>((ref) {
  return () => _backupInstalledApps(ref);
});

/// Backs up the list of cataloged installed apps as an encrypted AppStack (kind 30267)
/// to Nostr social relays. Only apps that are both installed and in the Zapstore
/// catalog are included.
///
/// Returns the count of backed-up apps.
/// Throws if user is not signed in or on failure.
Future<int> _backupInstalledApps(Ref ref) async {
  final signer = ref.read(Signer.activeSignerProvider);
  final signedInPubkey = ref.read(Signer.activePubkeyProvider);

  if (signer == null || signedInPubkey == null) {
    throw StateError('Sign in required to backup installed apps');
  }

  final pmState = ref.read(packageManagerProvider);
  final installedIds = pmState.installed.keys.toSet();

  if (installedIds.isEmpty) {
    return _saveBackup(ref, signer, []);
  }

  final platform = ref.read(packageManagerProvider.notifier).platform;
  final storage = ref.read(storageNotifierProvider.notifier);

  final apps = await storage.query(
    RequestFilter<App>(
      tags: {
        '#d': installedIds,
        '#f': {platform},
      },
    ).toRequest(subscriptionPrefix: 'installed-backup'),
    source: const LocalSource(),
  );

  final appIds = apps
      .map((app) => '${app.event.kind}:${app.pubkey}:${app.identifier}')
      .toList();

  return _saveBackup(ref, signer, appIds);
}

Future<int> _saveBackup(Ref ref, Signer signer, List<String> appIds) async {
  final platform = ref.read(packageManagerProvider.notifier).platform;
  final storage = ref.read(storageNotifierProvider.notifier);

  final partialStack = PartialAppStack.withEncryptedApps(
    name: 'Installed Apps',
    identifier: kInstalledAppsBackupIdentifier,
    apps: appIds,
    platform: platform,
  );

  final signedStack = await partialStack.signWith(signer);

  await storage.save({signedStack});

  storage.publish(
    {signedStack},
    source: RemoteSource(relays: 'social'),
  );

  return appIds.length;
}

/// Provider that exposes the restore function with correct Ref.
/// Returns decrypted addressable IDs from the backup (lightweight, no catalog queries).
final restoreInstalledAppsProvider = Provider<Future<List<String>> Function()>((ref) {
  return () => _restoreInstalledApps(ref);
});

/// Fetches the encrypted backup from Nostr relays and decrypts it.
/// Returns raw addressable IDs (e.g. "32267:pubkey:identifier").
/// The UI is responsible for resolving these to App models reactively.
Future<List<String>> _restoreInstalledApps(Ref ref) async {
  final signer = ref.read(Signer.activeSignerProvider);
  final signedInPubkey = ref.read(Signer.activePubkeyProvider);

  if (signer == null || signedInPubkey == null) {
    throw StateError('Sign in required to restore installed apps');
  }

  final storage = ref.read(storageNotifierProvider.notifier);

  final stacks = await storage.query(
    RequestFilter<AppStack>(
      authors: {signedInPubkey},
      tags: {'#d': {kInstalledAppsBackupIdentifier}},
    ).toRequest(subscriptionPrefix: 'installed-restore'),
    source: const LocalAndRemoteSource(relays: 'social', stream: false),
  );

  final stack = stacks.firstOrNull;
  if (stack == null || stack.content.isEmpty) {
    return [];
  }

  List<String> addressableIds;
  try {
    final decryptedContent = await signer.nip44Decrypt(
      stack.content,
      signedInPubkey,
    );
    addressableIds = (jsonDecode(decryptedContent) as List).cast<String>();
  } catch (e) {
    debugPrint('[InstalledAppsBackup] Restore decrypt failed: $e');
    return [];
  }

  return addressableIds;
}
