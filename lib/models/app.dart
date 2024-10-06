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

  App(
      {super.createdAt,
      super.content,
      super.tags,
      required this.developer,
      required this.releases,
      required this.signer});

  App.fromJson(super.map)
      : developer = belongsTo(map['developer']),
        releases = hasMany(map['releases']),
        signer = belongsTo(map['signer']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  String? get installedVersion => DataModel.adapterFor(this)
      .ref
      .read(installedAppProvider)[id!.toString()]
      ?.$1;

  int? get installedVersionCode => DataModel.adapterFor(this)
      .ref
      .read(installedAppProvider)[id!.toString()]
      ?.$2;

  FileMetadata? get latestMetadata {
    return releases.ordered.firstOrNull?.artifacts
        .where((a) =>
            a.mimeType == 'application/vnd.android.package-archive' &&
            a.platforms.contains('android-arm64-v8a'))
        .firstOrNull;
  }

  bool get canInstall => status == AppInstallStatus.installable;
  bool get canUpdate => status == AppInstallStatus.updatable;
  bool get isUpdated => status == AppInstallStatus.updated;

  AppInstallStatus get status {
    if (releases.isEmpty) {
      return AppInstallStatus.loading;
    }
    if (releases.isNotEmpty && latestMetadata == null) {
      return AppInstallStatus.differentArchitecture;
    }
    if (installedVersion == null) {
      return AppInstallStatus.installable;
    }
    var comp = 0;
    if (latestMetadata!.versionCode != null && id != 'store.zap.app') {
      // Note: need to exclude zap.store because development versions always
      // carry a lower version code (e.g. 12) than published ones (e.g. 2012)
      comp = latestMetadata!.versionCode!.compareTo(installedVersionCode!);
    }

    if (comp == 0) {
      comp = latestMetadata!.version!.compareTo(installedVersion!);
    }

    if (comp == 1) return AppInstallStatus.updatable;
    if (comp == 0) return AppInstallStatus.updated;
    // else it's a downgrade, which is not installable
    return AppInstallStatus.downgrade;
  }

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
        await adapter.getInstalledAppsMap();
        saveLocal();
      }
      notifier.state = IdleInstallProgress();
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
  ProviderSubscription? _sub;

  @override
  Future<void> onInitialized() async {
    if (!inIsolate) {
      _sub = ref.listen(installedAppProvider, (_, __) {
        triggerNotify();
      });
    }
    super.onInitialized();
  }

  @override
  void dispose() {
    _sub?.close();
    super.dispose();
  }

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
      '#m': [kAndroidMimeType]
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
    final m1 = DateTime.now().millisecondsSinceEpoch;
    print('enters findall');
    final map = await getInstalledAppsMap(defer: true);
    final m2 = DateTime.now().millisecondsSinceEpoch;

    if (params!.containsKey('installed')) {
      if (map.keys.isNotEmpty) {
        params['#d'] = map.keys;
        params.remove('installed');
        print('filtering by installed ${params['#d']}');
        final z = await loadAppModels(params);
        final m3 = DateTime.now().millisecondsSinceEpoch;
        print('finish ${m3 - m1} ${m2 - m1}');
        return z;
      }
    }
    return await loadAppModels(params);
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

  // TODO Make this async on writes / sync on reads (and return a class)
  Future<Map<String, (String, int)>> getInstalledAppsMap(
      {bool defer = false}) async {
    late List<PackageInfo>? infos;
    if (Platform.isAndroid) {
      infos = await packageManager.getInstalledPackages();
    } else {
      infos = [];
    }

    final installedPackageInfos = infos!.where((i) => ![
          'android',
          'com.android',
          'com.google',
          'org.chromium.webview_shell',
          'app.grapheneos',
          'app.vanadium'
        ].any((e) => i.packageName!.startsWith(e)));

    final newState = {
      for (final info in installedPackageInfos)
        info.packageName!: (info.versionName ?? '?', info.versionCode!)
    };

    // Providers can't set other providers state
    // while initializing, so defer setting state
    if (defer) {
      Future.microtask(() {
        ref.read(installedAppProvider.notifier).state = newState;
      });
      return newState;
    }

    return ref.read(installedAppProvider.notifier).state = newState;
  }

  @override
  DeserializedData<App> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      map['signer'] = map['pubkey'];
      final zapTags = (map['tags'] as Iterable).where((t) => t[0] == 'zap');
      if (zapTags.length == 1) {
        map['developer'] = (zapTags.first as List)[1];
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

final installedAppProvider =
    StateProvider<Map<String, (String, int)>>((_) => {});

final installationProgressProvider =
    StateProvider.family<AppInstallProgress, String>(
        (_, arg) => IdleInstallProgress());
