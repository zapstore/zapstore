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

  FileMetadata? get latestMetadata {
    return releases.ordered.firstOrNull?.artifacts
        .where((a) =>
            a.mimeType == kAndroidMimeType &&
            a.platforms.contains('android-arm64-v8a'))
        .firstOrNull;
  }

  bool get canInstall => localApp.value?.status == AppInstallStatus.installable;
  bool get canUpdate => localApp.value?.status == AppInstallStatus.updatable;
  bool get isUpdated => localApp.value?.status == AppInstallStatus.updated;

  Future<void> install() async {
    if (!canInstall && !canUpdate) {
      return;
    }

    final adapter = DataModel.adapterFor(this) as AppAdapter;
    final notifier =
        adapter.ref.read(installationProgressProvider(id!.toString()).notifier);

    final installPermission = await Permission.requestInstallPackages.status;
    if (!installPermission.isGranted) {
      final newStatus = await Permission.requestInstallPackages.request();
      if (newStatus.isDenied) {
        notifier.state =
            ErrorInstallProgress(Exception('Installation permission denied'));
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
        const e =
            'Hash mismatch, possibly a malicious file. Aborting installation.';
        await file.delete();
        notifier.state = ErrorInstallProgress(Exception(e));
        return;
      }
      notifier.state = HashVerifiedInstallProgress();

      final result = await InstallPlugin.install(file.path);
      if (result['isSuccess']) {
        await file.delete();
        await adapter.ref.localApps.localAppAdapter
            .updateInstallStatus(appId: id?.toString());
        notifier.state = IdleInstallProgress();
      } else {
        const msg = 'Android rejected installation';
        notifier.state = ErrorInstallProgress(Exception(msg));
      }
    }

    final fileExists = await file.exists();
    if (fileExists && await file.length() == size) {
      await installOnDevice();
    } else {
      if (fileExists) {
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
        await installOnDevice();
      });
    }
  }

  Future<bool?> packageCertificateMatches() async {
    final flags = PackageInfoFlags(
      {PMFlag.getPermissions, PMFlag.getSigningCertificates},
    );

    final i = await packageManager.getPackageInfo(
        packageName: id!.toString(), flags: flags);
    if (i == null || latestMetadata!.apkSignatureHash == null) {
      return null;
    }
    final bytes = i.signingInfo!.signingCertificateHistory!.first;
    return latestMetadata!.apkSignatureHash!.toLowerCase() ==
        sha256.convert(bytes).toString().toLowerCase();
  }
}

mixin AppAdapter on Adapter<App> {
  Future<List<App>> loadAppModels(Map<String, dynamic> params) async {
    final apps = await super.findAll(
      params: {
        ...params,
        '#f': ['android-arm64-v8a'],
      },
    );
    // Find all appid@version ($3) as we need to pick one tag to query on
    // (filters by kind ($1) and pubkey ($2) done locally)
    final latestReleaseIdentifiers =
        apps.map((app) => app.linkedReplaceableEvents.firstOrNull?.$3).nonNulls;
    final releases = await ref.releases.findAll(
      params: {'#d': latestReleaseIdentifiers},
    );
    // TODO: Deprecated, will be removed
    // Some developers without access to the latest zapstore-cli
    // have not published their apps with latest release identifiers
    // so load as usual
    final oldApps =
        apps.where((app) => !latestReleaseIdentifiers.contains(app.identifier));
    final oldReleases = await ref.releases.findAll(
      params: {
        '#a': oldApps.map((app) => app.getReplaceableEventLink().formatted)
      },
    );

    final metadataIds = [...releases, ...oldReleases]
        .map((r) => r.tagMap['e'])
        .nonNulls
        .expand((_) => _);

    final userIds = {
      for (final app in apps) app.signer.id,
      for (final app in apps) app.developer.id
    }.nonNulls;

    // Metadata and users probably go to separate relays
    // so query in parallel
    await Future.wait([
      ref.fileMetadata.findAll(params: {
        'ids': metadataIds,
        '#m': [kAndroidMimeType],
        '#f': ['android-arm64-v8a'],
      }),
      ref.users.findAll(params: {'authors': userIds})
    ]);

    return apps;
  }

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
      final appIds = await _installedIdentifiers();
      if (appIds.isNotEmpty) {
        params['#d'] = appIds;
        params.remove('installed');
        final apps = await loadAppModels(params);
        // Once apps are loaded, check for installed status
        await ref.localApps.localAppAdapter.updateInstallStatus();
        return apps;
      }
    }
    return await loadAppModels(params);
  }

  List<App> findWhereIdInLocal(Iterable<String> appIds) {
    const len = 5 + 64 + 3; // kind + pubkey + separators length
    final result = db.select(
        'SELECT key, data, substr(json_extract(data, \'\$.id\'), $len) AS id from apps where id in (${appIds.map((_) => '?').join(', ')})',
        appIds.toList());
    return deserializeFromResult(result);
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
    final apps = await loadAppModels({
      ...params!,
      '#d': [id]
    });
    // If ID not found in relay then clear from local storage
    if (apps.isEmpty) {
      deleteLocalById(id);
      return null;
    }
    await ref.localApps.localAppAdapter
        .updateInstallStatus(appId: id.toString());
    return apps.first;
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
      map['signer'] = map['pubkey'];
      map['developer'] = tagMap['zap']?.firstOrNull;
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

// class

sealed class AppInstallProgress {}

class IdleInstallProgress extends AppInstallProgress {}

class DownloadingInstallProgress extends AppInstallProgress {
  final double progress;
  DownloadingInstallProgress(this.progress);
}

class VerifyingHashProgress extends AppInstallProgress {}

class HashVerifiedInstallProgress extends AppInstallProgress {}

class ErrorInstallProgress extends AppInstallProgress {
  final Exception e;
  ErrorInstallProgress(this.e);
}

final installationProgressProvider =
    StateProvider.family<AppInstallProgress, String>(
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
