import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/secure_storage_service.dart';
import 'package:zapstore/utils/extensions.dart';

const _logPrefix = '[InstalledAppsBackup]';

/// Persisted setting for auto-backup (off by default). Survives sign-out.
class BackupSettingsNotifier extends AsyncNotifier<bool> {
  @override
  Future<bool> build() async {
    final storage = ref.read(secureStorageServiceProvider);
    return storage.getBackupEnabled();
  }

  Future<void> setEnabled(bool enabled) async {
    await ref.read(secureStorageServiceProvider).setBackupEnabled(enabled);
    ref.invalidateSelf();
  }
}

final backupSettingsProvider =
    AsyncNotifierProvider<BackupSettingsNotifier, bool>(
  BackupSettingsNotifier.new,
);

const _backupDebounceDuration = Duration(seconds: 3);

/// Listens to batch completion and uninstalls; triggers backup when enabled and signed in.
/// Debounces rapid triggers (e.g. multiple uninstalls) to avoid repeated sign prompts.
/// Watched by MainScaffold to keep alive.
final installedAppsBackupListenerProvider = Provider<void>((ref) {
  Timer? debounceTimer;
  var activeBatchAppIds = <String>{};
  Set<String>? baselineInstalledKeys;

  void scheduleBackup() {
    debounceTimer?.cancel();
    debounceTimer = Timer(_backupDebounceDuration, () {
      debounceTimer = null;
      _maybeBackup(ref);
    });
  }

  ref.listen<BatchProgress?>(batchProgressProvider, (prev, next) {
    if (next?.hasInProgress == true) {
      activeBatchAppIds = ref
          .read(packageManagerProvider)
          .operations
          .entries
          .where((entry) => entry.value.isInProgress)
          .map((entry) => entry.key)
          .toSet();
    }

    final hasBatchCompleted =
        prev?.hasInProgress == true && next?.isAllComplete == true;
    if (hasBatchCompleted) {
      if (baselineInstalledKeys == null) return;
      if (_isRestoreOnlyBatch(ref, activeBatchAppIds)) {
        activeBatchAppIds = <String>{};
        baselineInstalledKeys =
            ref.read(packageManagerProvider).installed.keys.toSet();
        return;
      }
      baselineInstalledKeys =
          ref.read(packageManagerProvider).installed.keys.toSet();
      scheduleBackup();
      activeBatchAppIds = <String>{};
    }
  });

  ref.listen<bool>(
    packageManagerProvider.select((s) => s.isScanning),
    (prev, next) {
      if (prev == true && next == false) {
        final keys = ref.read(packageManagerProvider).installed.keys.toSet();
        baselineInstalledKeys ??= keys;
      }
    },
  );

  ref.listen<int>(
    packageManagerProvider.select((s) => s.installed.length),
    (prev, next) {
      if (baselineInstalledKeys == null || prev == null || prev == next) return;
      final currentKeys =
          ref.read(packageManagerProvider).installed.keys.toSet();
      final added = currentKeys.difference(baselineInstalledKeys!);
      final removed = baselineInstalledKeys!.difference(currentKeys);
      if (added.isEmpty && removed.isEmpty) return;
      // Skip isScanning only for additions (avoids false triggers during sync).
      // Removals are always from user uninstall — must trigger backup.
      if (removed.isEmpty && ref.read(packageManagerProvider).isScanning) return;
      final batch = ref.read(batchProgressProvider);
      if (batch != null && batch.hasInProgress) return;
      baselineInstalledKeys = currentKeys;
      scheduleBackup();
    },
  );

  ref.onDispose(() {
    debounceTimer?.cancel();
  });
});

void _maybeBackup(Ref ref) {
  final enabledAsync = ref.read(backupSettingsProvider);
  final enabled = enabledAsync.valueOrNull ?? false;
  final pubkey = ref.read(Signer.activePubkeyProvider);

  if (!enabled || pubkey == null) return;

  unawaited(
    _backupInstalledApps(ref).then((count) {
      debugPrint('$_logPrefix Backup completed: $count apps');
    }).catchError((e, st) {
      debugPrint('$_logPrefix Backup error: $e');
      debugPrint('$_logPrefix $st');
    }),
  );
}

bool _isRestoreOnlyBatch(Ref ref, Set<String> appIds) {
  if (appIds.isEmpty) return false;
  final pm = ref.read(packageManagerProvider.notifier);
  return appIds.every(
    (appId) => pm.getOperationSource(appId) == InstallSource.restore,
  );
}

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
    debugPrint('$_logPrefix Restore decrypt failed: $e');
    return [];
  }

  return addressableIds;
}
