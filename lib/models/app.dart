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
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as path;
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';

part 'app.g.dart';

const kAndroidMimeType = 'application/vnd.android.package-archive';

@DataAdapter([NostrAdapter, AppAdapter])
class App extends BaseApp with DataModelMixin<App> {
  final HasMany<Release> releases;
  final BelongsTo<User> signer;
  final BelongsTo<User> developer;

  App.fromJson(super.map)
      : developer = belongsTo(map['developer']),
        releases = hasMany(map['releases']),
        signer = belongsTo(map['signer']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  String? get installedVersion => tagMap['installedVersion']?.firstOrNull;
  int? get installedVersionCode =>
      int.tryParse(tagMap['installedVersionCode']?.firstOrNull ?? '');
  AppInstallStatus? get status {
    final _status = tagMap['status']?.firstOrNull;
    if (_status == null) return null;
    return AppInstallStatus.values.firstWhereOrNull((e) => e.name == _status);
  }

  FileMetadata? get latestMetadata {
    return releases.ordered.firstOrNull?.artifacts
        .where((a) =>
            a.mimeType == kAndroidMimeType &&
            a.platforms.contains('android-arm64-v8a'))
        .firstOrNull;
  }

  bool get canInstall => status == AppInstallStatus.installable;
  bool get canUpdate => status == AppInstallStatus.updatable;
  bool get isUpdated => status == AppInstallStatus.updated;

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
        var e = 'Hash mismatch, ';
        if (size == await file.length()) {
          e += 'likely a malicious file.';
        } else {
          e += 'bad data ($size is not ${await file.length()}).';
        }
        e += ' Please try again.';
        await file.delete();
        notifier.state = ErrorInstallProgress(Exception(e));
        return;
      }
      notifier.state = HashVerifiedInstallProgress();

      final result = await InstallPlugin.install(file.path);
      if (result['isSuccess']) {
        await file.delete();
        await adapter.updateInstallStatus(app: this);
        notifier.state = IdleInstallProgress();
      } else {
        const msg = 'Android rejected installation';
        notifier.state = ErrorInstallProgress(Exception(msg));
      }
    }

    if (await file.exists()) {
      await installOnDevice();
    } else {
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
    final includes = params.remove('includes') ?? false;
    final apps = await super.findAll(params: params);
    final releases = await ref.releases.findAll(params: {
      '#a': apps.map((app) => app.getReplaceableEventLink().formatted)
    });
    final metadataIds =
        releases.map((r) => r.tagMap['e']).nonNulls.expand((_) => _);

    await ref.fileMetadata.findAll(params: {
      'ids': metadataIds,
      '#m': [kAndroidMimeType],
      '#f': ['android-arm64-v8a'],
    });

    if (includes) {
      final userIds = {
        for (final app in apps) app.signer.id,
        for (final app in apps) app.developer.id
      }.nonNulls;
      await ref.users.findAll(params: {'authors': userIds});
    }
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
        await loadAppModels(params);
        // Once apps are loaded, check for installed status
        return await updateInstallStatus();
      }
    }
    return await loadAppModels(params);
  }

  List<App> findWhereIdInLocal(Iterable<String> appIds) {
    final result = db.select(
        'SELECT key, data, json_extract(data, \'\$.id\') AS id from apps where id in (${appIds.map((_) => '?').join(', ')})',
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
            ![
              'android',
              'com.android',
              'com.google',
              'org.chromium.webview_shell',
              'app.grapheneos',
              'app.vanadium'
            ].any((e) => i.packageName!.startsWith(e)))
          i.packageName!,
    };
  }

  Future<List<App>> updateInstallStatus({App? app}) async {
    if (!Platform.isAndroid) {
      return [];
    }

    final updatedApps = <App>[];
    final infos = app != null
        ? [await packageManager.getPackageInfo(packageName: app.id.toString())]
            .nonNulls
        : await packageManager.getInstalledPackages();

    final installedPackageInfos = infos!.where((i) => ![
          'android',
          'com.android',
          'com.google',
          'org.chromium.webview_shell',
          'app.grapheneos',
          'app.vanadium'
        ].any((e) => i.packageName!.startsWith(e)));

    final apps = findManyLocalByIds(
        installedPackageInfos.map((i) => i.packageName).nonNulls);

    for (final i in installedPackageInfos) {
      var app = apps.firstWhereOrNull((app) => i.packageName == app.id);
      final installedVersion = i.versionName;
      final installedVersionCode = i.versionCode;

      if (app == null) continue;

      final AppInstallStatus status =
          _determineInstallStatus(app, installedVersion, installedVersionCode);

      if (app.installedVersionCode != installedVersionCode) {
        app.addTags({
          ('installedVersion', installedVersion),
          ('installedVersionCode', installedVersionCode),
          ('status', status.name)
        }, replace: true);
        app.saveLocal();
      }

      updatedApps.add(app);
    }

    // Update number of apps
    final rs = db.select(
        'SELECT count(*) as c FROM apps, json_each(json_extract(apps.data, \'\$.tags\')) WHERE json_extract(value, \'\$[0]\') is \'status\' AND json_extract(value, \'\$[1]\') is \'updatable\'');
    ref.read(appsToUpdateProvider.notifier).state = rs.first['c'];

    return updatedApps;
  }

  AppInstallStatus _determineInstallStatus(
      App app, String? installedVersion, int? installedVersionCode) {
    if (app.releases.isEmpty) {
      return AppInstallStatus.loading;
    }

    if (app.releases.isNotEmpty && app.latestMetadata == null) {
      return AppInstallStatus.differentArchitecture;
    }
    if (installedVersion == null) {
      return AppInstallStatus.installable;
    }
    var comp = 0;
    if (app.latestMetadata!.versionCode != null &&
        installedVersionCode != null &&
        app.id != 'store.zap.app') {
      // Note: need to exclude zap.store because development versions always
      // carry a lower version code (e.g. 12) than published ones (e.g. 2012)
      comp = app.latestMetadata!.versionCode!.compareTo(installedVersionCode);
    }

    if (comp == 0) {
      comp = app.latestMetadata!.version!.compareTo(installedVersion);
    }

    if (comp == 1) return AppInstallStatus.updatable;
    if (comp == 0) return AppInstallStatus.updated;
    // else it's a downgrade, which is not installable
    return AppInstallStatus.downgrade;
  }

  @override
  DeserializedData<App> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      e['id'] = (e['tags'] as Iterable).where((t) => t[0] == 'd').first.last;
    }

    final savedApps = findManyLocalByIds(list.map((e) => e['id']));
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      final tags = map['tags'] as Iterable;
      map['signer'] = map['pubkey'];
      final zapTags = tags.where((t) => t[0] == 'zap');
      if (zapTags.length == 1) {
        map['developer'] = (zapTags.first as List)[1];
      }
    }

    final deserialized = super.deserialize(data);
    // Preserve installation data
    for (final app in deserialized.models) {
      final savedApp = savedApps.firstWhereOrNull((s) => s.id == app.id);
      app.addTags({
        ('installedVersion', savedApp?.installedVersion),
        ('installedVersionCode', savedApp?.installedVersionCode),
        ('status', savedApp?.status?.name)
      }, replace: true);
    }
    return deserialized;
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

// install support

enum AppInstallStatus {
  updated,
  updatable,
  installable,
  downgrade,
  differentArchitecture,
  loading
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
