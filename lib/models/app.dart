import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:android_package_manager/android_package_manager.dart';
import 'package:async/async.dart';
import 'package:collection/collection.dart';
import 'package:convert/convert.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:install_plugin/install_plugin.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as path;
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';

part 'app.g.dart';

@DataAdapter([NostrAdapter, AppAdapter])
class App extends BaseApp with DataModelMixin<App> {
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

  Future<void> install() async {
    if (!canInstall && !canUpdate) {
      return;
    }

    final adapter = DataModel.adapterFor(this) as AppAdapter;
    final notifier =
        adapter.ref.read(installationProgressProvider(id!).notifier);

    if (canUpdate) {
      final match = await packageCertificateMatches();
      if (match == false) {
        notifier.state = ErrorInstallProgress(
          Exception('Update is not possible'),
          info:
              'App failed a security check (APK cert mismatch). To fix, remove the app.',
        );
        return;
      }
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
    final size = latestMetadata!.size ?? 0;

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
      final result = await InstallPlugin.install(file.path);

      if (result['isSuccess']) {
        await file.delete();
        await adapter.ref.localApps.localAppAdapter
            .refreshUpdateStatus(appId: identifier);
      } else {
        const msg = 'App not installed';
        notifier.state = ErrorInstallProgress(
          Exception(msg),
          info: result['errorMessage'],
        );
      }
    }

    final fileExists = await file.exists();
    if (fileExists && await file.length() == size) {
      await installOnDevice();
    } else {
      if (fileExists) {
        // If file exists download was probably partial, remove
        await file.delete();
      }

      StreamSubscription? sub;
      final client = http.Client();
      final sink = file.openWrite();

      final backupUrl = 'https://cdn.zap.store/$hash';
      final url = latestMetadata!.urls.firstOrNull ?? backupUrl;
      var downloadedBytes = 0;
      Uri uri;

      var response =
          await client.send(http.Request('GET', uri = Uri.parse(url)));
      if (response.statusCode != 200) {
        uri = Uri.parse(backupUrl);
        response = await client.send(http.Request('GET', uri));
      }
      final totalBytes = response.contentLength ?? size;

      sub = response.stream.listen((chunk) {
        final data = Uint8List.fromList(chunk);
        sink.add(data);
        downloadedBytes += data.length;
        notifier.state =
            DownloadingInstallProgress(downloadedBytes / totalBytes);
      }, onError: (e) {
        throw e;
      }, onDone: () async {
        await sub?.cancel();
        await sink.close();
        client.close();
        if (downloadedBytes == totalBytes) {
          await installOnDevice();
        } else {
          notifier.state = ErrorInstallProgress(
              Exception('App did not fully download.'),
              info: 'Please try again.');
        }
      });
    }
  }

  Future<bool?> packageCertificateMatches() async {
    if (latestMetadata!.apkSignatureHash == null) return null;

    final flags = PackageInfoFlags(
      {PMFlag.getPermissions, PMFlag.getSigningCertificates},
    );

    final i = await packageManager.getPackageInfo(
        packageName: identifier!, flags: flags);
    if (i == null) {
      return null;
    }
    final bytes = i.signingInfo!.signingCertificateHistory!.first;
    final installedApkSigHash = sha256.convert(bytes).toString().toLowerCase();
    final metadataSigHashes =
        latestMetadata?.tagMap['apk_signature_hash'] ?? {};
    return metadataSigHashes
        .any((msh) => msh.toLowerCase() == installedApkSigHash);
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
          ? apps.fold(apps.first.createdAt!, (acc, e) {
              return acc.isAfter(e.createdAt!) ? acc : e.createdAt!;
            })
          : null;

      if (m != null) {
        for (final app in apps) {
          queriedAtMap[app.identifier!] = m;
        }
      }
    } else {
      // Search (which uses no #d)
      apps = await super.findAll(params: params);
    }

    // Find all appid@version ($3) as we need to pick one tag to query on
    // (filters by kind ($1) and pubkey ($2) done locally)
    final latestReleaseIdentifiers =
        apps.map((app) => app.linkedReplaceableEvents.firstOrNull?.$3).nonNulls;
    if (latestReleaseIdentifiers.isNotEmpty) {
      releases = await ref.releases.findAll(
        params: {'#d': latestReleaseIdentifiers},
      );
    } else {
      releases = [];
    }

    // TODO: Deprecated, will be removed
    // Some developers without access to the latest zapstore-cli
    // have not published their apps with latest release identifiers
    // so load as usual
    final deprecatedApps =
        apps.where((app) => app.linkedReplaceableEvents.isEmpty);
    if (deprecatedApps.isNotEmpty) {
      final deprecatedReleases = await ref.releases.findAll(
        params: {
          '#a': deprecatedApps
              .map((app) => app.getReplaceableEventLink().formatted)
        },
      );
      final groupedDeprecatedReleases =
          deprecatedReleases.groupListsBy((r) => r.app.value!);
      for (final e in groupedDeprecatedReleases.entries) {
        final mostRecentRelease = e.value.sortedByLatest.first;
        releases.add(mostRecentRelease);
      }
    }
    // End deprecated zone

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
      final tagMap = tagsToMap(map['tags']);
      map['developer'] = tagMap['zap']?.firstOrNull ??
          (map['pubkey'] != kZapstorePubkey ? map['pubkey'] : null);
      map['localApp'] = tagMap['d']!.first;
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
        // Chunk size determined from approximate cpu/memory profiling
        final chunk = await reader.readChunk(2048);
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
      sorted((a, b) => b.createdAt!.compareTo(a.createdAt!));
}

// class

sealed class AppInstallProgress {}

class IdleInstallProgress extends AppInstallProgress {}

class DownloadingInstallProgress extends AppInstallProgress {
  final double progress;
  DownloadingInstallProgress(this.progress);
}

class VerifyingHashProgress extends AppInstallProgress {}

class RequestInstallProgress extends AppInstallProgress {}

class ErrorInstallProgress extends AppInstallProgress {
  final Exception e;
  final String? info;
  ErrorInstallProgress(this.e, {this.info});
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
