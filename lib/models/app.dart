import 'dart:async';
import 'dart:io';
import 'dart:isolate';

import 'package:async/async.dart';
import 'package:background_downloader/background_downloader.dart';
import 'package:collection/collection.dart';
import 'package:convert/convert.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:install_plugin/install_plugin.dart';
import 'package:mutex/mutex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:purplebase/purplebase.dart' as base;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';
import 'package:path/path.dart' as path;
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';

part 'app.g.dart';

final mutex = Mutex();

@DataAdapter([NostrAdapter, AppAdapter])
class App extends base.App with DataModelMixin<App> {
  @override
  Object? get id => event.id;

  final HasMany<Release> releases;
  final BelongsTo<User> signer;
  final BelongsTo<User> developer;
  final BelongsTo<LocalApp> localApp;

  App.fromJson(super.map)
      : developer = belongsTo(map['developer']),
        releases = hasMany(map['releases']),
        signer = belongsTo(map['signer']),
        localApp = belongsTo(map['localApp']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  Release? get latestRelease {
    return releases.toList().sortedByLatest.firstOrNull;
  }

  FileMetadata? get latestMetadata {
    return latestRelease?.artifacts
        // All artifacts *should* be APKs for the
        // current architecture, but double check
        .where((a) =>
            a.mimeType == kAndroidMimeType &&
            a.platforms.contains('android-arm64-v8a'))
        .firstOrNull;
  }

  bool get canInstall =>
      localApp.value?.status == null &&
      latestMetadata != null &&
      signer.isPresent;
  bool get canUpdate =>
      localApp.value?.status == AppInstallStatus.updatable &&
      latestMetadata != null &&
      signer.isPresent;
  bool get isUpdated => localApp.value?.status == AppInstallStatus.updated;
  bool get isDowngrade => localApp.value?.status == AppInstallStatus.downgrade;
  bool get hasCertificateMismatch =>
      localApp.value?.status == AppInstallStatus.certificateMismatch;

  Future<void> install({bool alwaysTrustSigner = false}) async {
    if (!canInstall && !canUpdate || hasCertificateMismatch) {
      throw Exception(
          'Local status: ${localApp.value?.status}, signer: ${signer.value?.npub}, certificate mismatch: $hasCertificateMismatch');
    }

    final adapter = DataModel.adapterFor(this) as AppAdapter;
    final notifier =
        adapter.ref.read(installationProgressProvider(id!).notifier);

    // Persist trusted preference
    if (alwaysTrustSigner) {
      final settings = adapter.ref.settings.findOneLocalById('_')!;
      settings.trustedUsers.add(signer.value!);
      settings.saveLocal();
    }

    final installPermission = await Permission.requestInstallPackages.status;
    if (!installPermission.isGranted) {
      final newStatus = await Permission.requestInstallPackages.request();
      if (newStatus.isDenied) {
        notifier.state = ErrorInstallProgress(Exception('Error'),
            info: 'Installation permission denied by user');
        return;
      }
    }

    final hash = latestMetadata!.hash!;
    final dir = await getApplicationSupportDirectory();
    final file = File(path.join(dir.path, hash));

    installOnDevice() async {
      notifier.state = VerifyingHashProgress();

      if (await _isHashMismatch(file.path, hash)) {
        const e = 'Hash mismatch';
        await file.delete();
        notifier.state = ErrorInstallProgress(Exception(e),
            info: 'Possibly a malicious file. Aborting installation.');
        return;
      }

      notifier.state = RequestInstallProgress();

      // NOTE: Using https://github.com/hui-z/flutter_install_plugin
      // Tried https://github.com/zapstore/android_package_installer
      // but it is giving users problems (and for me it's slower)
      // Probably need to fork hui-z's with support for user-canceled action
      // NOTE: Must use mutex: https://github.com/hui-z/flutter_install_plugin/issues/68
      final result = await mutex.protect(() async {
        return InstallPlugin.install(file.path);
      });

      if (result['isSuccess']) {
        notifier.state = IdleInstallProgress(success: true);
        await file.delete();
        await adapter.ref.localApps.localAppAdapter
            .refreshUpdateStatus(appId: identifier);
        Future.microtask(() {
          notifier.state = IdleInstallProgress();
        });
      } else {
        const msg = 'App not installed';
        notifier.state = ErrorInstallProgress(
          Exception(msg),
          info: 'User canceled or error occured: ${result['errorMessage']}',
        );
      }
    }

    final fileExists = await file.exists();
    if (fileExists) {
      await installOnDevice();
    } else {
      if (fileExists) {
        // If file exists download was probably partial, remove
        await file.delete();
      }

      var url = latestMetadata!.urls.first;
      // TODO: Remove in 0.2.x
      if (url.startsWith('https://cdn.zap.store')) {
        url = url.replaceFirst(
            'https://cdn.zap.store', 'https://cdn.zapstore.dev');
      }

      final (baseDirectory, directory, filename) =
          await Task.split(filePath: file.path);

      final task = DownloadTask(
        url: url,
        baseDirectory: baseDirectory,
        directory: directory,
        filename: filename,
        updates: Updates.progress,
      );

      final result = await FileDownloader().download(
        task,
        onProgress: (progress) {
          notifier.state = DownloadingInstallProgress(progress);
        },
      );

      switch (result.status) {
        case TaskStatus.complete:
          return await installOnDevice();
        default:
          notifier.state = ErrorInstallProgress(
              Exception('App did not fully download.'),
              info: 'Please try again.');
          await file.delete();
      }
    }
  }
}

mixin AppAdapter on Adapter<App> {
  final queriedAtMap = <String, DateTime>{};

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
    params ??= {};
    if (params.remove('includes') ?? false) {
      return await fetchAppModels(params);
    }
    return super.findAll(params: params);
  }

  Future<void> checkForUpdates() async {
    final appIds = await _installedIdentifiers();
    if (appIds.isNotEmpty) {
      await fetchAppModels({'#d': appIds});
      // Once apps are loaded, check for installed status
      await ref.localApps.localAppAdapter.refreshUpdateStatus();
    }
  }

  Future<List<App>> fetchAppModels(Map<String, dynamic> params) async {
    late final List<App> apps;
    late final List<Release> releases;

    // If looking up multiple apps via `#d`, use `queriedAtMap`
    // to find the earliest queried at Date, to be used in the
    // next request `since` field
    if (params.containsKey('#d')) {
      DateTime? earliestQueryAt;
      final identifiers = <String>{...params['#d']};

      final cachedIdentifiers =
          identifiers.where((i) => queriedAtMap.containsKey(i));
      final newIdentifiers =
          identifiers.where((i) => !queriedAtMap.containsKey(i));

      var cachedApps = <App>[];
      var newApps = <App>[];

      // For apps already in cache, find earliest date of the set
      // (as we cannot have one `since` per app)
      if (cachedIdentifiers.isNotEmpty) {
        earliestQueryAt = cachedIdentifiers
            .fold<DateTime>(queriedAtMap[cachedIdentifiers.first]!, (acc, e) {
          return acc.isBefore(queriedAtMap[e]!) ? acc : queriedAtMap[e]!;
        });

        cachedApps = await super.findAll(
          params: {
            '#d': cachedIdentifiers,
            // Query since latest + 1
            'since': earliestQueryAt.add(Duration(seconds: 1)),
          },
        );
      }

      // For apps not in cache, query without a since
      if (newIdentifiers.isNotEmpty) {
        newApps = await super.findAll(
          params: {
            '#d': newIdentifiers,
          },
        );
      }

      apps = [...cachedApps, ...newApps];

      // Find most recent `created_at` from returned apps
      // and assign it to their queried at value for caching
      final m = apps.isNotEmpty
          ? apps.fold(apps.first.event.createdAt, (acc, e) {
              return acc.isAfter(e.event.createdAt) ? acc : e.event.createdAt;
            })
          : null;

      if (m != null) {
        for (final app in apps) {
          queriedAtMap[app.identifier] = m;
        }
      }
    } else {
      // Search (which uses no #d)
      apps = await super.findAll(params: params);
    }

    // Find all appid@version ($3) as we need to pick one tag to query on
    // (filters by kind ($1) and pubkey ($2) done locally)
    final latestReleaseIdentifiers = apps
        .map((app) => app.event.linkedReplaceableEvents.firstOrNull?.$3)
        .nonNulls;
    if (latestReleaseIdentifiers.isNotEmpty) {
      releases = await ref.releases.findAll(
        params: {'#d': latestReleaseIdentifiers},
      );
    } else {
      releases = [];
    }

    await nostrAdapter.loadArtifactsAndUsers(releases);
    await ref.localApps.localAppAdapter.refreshUpdateStatus();

    return apps;
  }

  @override
  Future<App?> findOne(Object id,
      {bool remote = true,
      bool background = false,
      Map<String, dynamic>? params = const {},
      Map<String, String>? headers,
      OnSuccessOne<App>? onSuccess,
      OnErrorOne<App>? onError,
      DataRequestLabel? label}) async {
    final apps = await fetchAppModels({
      ...params ?? {},
      '#d': [id]
    });
    // If ID not found in relay then clear from local storage
    if (apps.isEmpty) {
      deleteLocalById(id);
      return null;
    }
    await ref.localApps.localAppAdapter
        .refreshUpdateStatus(appId: id.toString());
    return apps.first;
  }

  List<App> findWhereIdentifierInLocal(Iterable<String> appIds) {
    const len = 5 + 64 + 3; // kind + pubkey + separators length
    final result = db.select(
        'SELECT key, data, substr(json_extract(data, \'\$.id\'), $len) AS id from apps where id in (${appIds.map((_) => '?').join(', ')})',
        appIds.toList());
    return deserializeFromResult(result);
  }

  Future<Set<String>> _installedIdentifiers() async {
    if (!Platform.isAndroid) {
      return {};
    }
    final infos = await packageManager.getInstalledPackages();
    return {
      for (final i in infos!)
        if (i.packageName != null &&
            !kExcludedAppIdNamespaces.any((e) => i.packageName!.startsWith(e)))
          i.packageName!,
    };
  }

  @override
  DeserializedData<App> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];

    for (final Map<String, dynamic> map in list) {
      final tags = map['tags'];
      final pubkey = map['pubkey'];
      map['localApp'] = base.BaseUtil.getTag(tags, 'd')!;
      // Find zap recipient as specified in event or fall back to author's pubkey
      map['developer'] = base.BaseUtil.getTag(tags, 'zap') ?? pubkey;
      // If app is signed by Zapstore (except the Zapstore app), remove from being the developer
      if (pubkey == kZapstorePubkey &&
          map['localApp'] != kZapstoreAppIdentifier) {
        map.remove('developer');
      }
    }

    return super.deserialize(data);
  }
}

Future<bool> _isHashMismatch(String path, String hash) async {
  return await Isolate.run(() async {
    final file = File(path);
    final fileStream = file.openRead();
    final reader = ChunkedStreamReader(fileStream);
    final digestOutputSink = AccumulatorSink<Digest>();
    final digestInputSink = sha256.startChunkedConversion(digestOutputSink);
    String? digest;
    try {
      while (true) {
        // Chunk size determined from rough CPU/memory profiling
        final chunk = await reader.readChunk(3072);
        if (chunk.isEmpty) {
          break; // EOF
        }
        digestInputSink.add(chunk);
      }
    } finally {
      digestInputSink.close();
      digest = digestOutputSink.events.single.toString();
      digestOutputSink.close();
      reader.cancel();
    }
    return digest != hash;
  });
}

extension AppExt on Iterable<App> {
  List<App> get sortedByLatest =>
      sorted((a, b) => b.event.createdAt.compareTo(a.event.createdAt));
}

// class

sealed class AppInstallProgress {}

class IdleInstallProgress extends AppInstallProgress {
  final bool? success;
  IdleInstallProgress({this.success});
}

class DownloadingInstallProgress extends AppInstallProgress {
  final double progress;
  DownloadingInstallProgress(this.progress);
}

class VerifyingHashProgress extends AppInstallProgress {}

class RequestInstallProgress extends AppInstallProgress {}

class ErrorInstallProgress extends AppInstallProgress {
  final Exception e;
  final String? info;
  final List<(String, Future<void> Function())> actions;
  ErrorInstallProgress(this.e, {this.info, this.actions = const []});
}

extension ExceptionExt on Exception {
  String get message => (this as dynamic).message ?? toString();
}

final installationProgressProvider =
    StateProvider.family<AppInstallProgress, Object>(
        (_, arg) => IdleInstallProgress());

final appsToUpdateProvider = StateProvider((_) => 0);

// Constants

const kAndroidMimeType = 'application/vnd.android.package-archive';

const kExcludedAppIdNamespaces = [
  'android',
  'com.android',
  'com.google',
  'com.samsung',
  'com.sec.android',
  'org.chromium.webview_shell',
  'app.grapheneos',
  'app.vanadium',
];
