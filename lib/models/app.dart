import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:android_package_manager/android_package_manager.dart';
import 'package:collection/collection.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:install_plugin/install_plugin.dart';
import 'package:json_annotation/json_annotation.dart';
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
import 'package:zapstore/screens/search_screen.dart';

part 'app.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, AppAdapter])
class App extends Event<App> with BaseApp {
  late final HasMany<Release> releases;
  late final BelongsTo<User> signer;
  late final BelongsTo<User> developer;

  String? get installedVersion =>
      DataModel.adapterFor(this).ref.read(installedAppProvider)[id!.toString()];

  FileMetadata? get latestMetadata {
    //   if (Platform.isAndroid) {
    //   final androidInfo = await deviceInfo.androidInfo;
    //   return androidInfo.architecture; // e.g. "arm64-v8a"
    // }
    return releases.latest?.artifacts
        .where((a) =>
            a.mimeType == 'application/vnd.android.package-archive' &&
            a.architectures.contains('arm64-v8a'))
        .firstOrNull;
  }
}

mixin AppAdapter on Adapter<App> {
  ProviderSubscription? _sub;
  AppLifecycleListener? _lifecycleListener;

  @override
  Future<void> onInitialized() async {
    if (!ref.read(localStorageProvider).inIsolate) {
      _sub = ref.listen(installedAppProvider, (_, __) {
        triggerNotify();
      });

      _lifecycleListener = AppLifecycleListener(
        onStateChange: (state) async {
          if (state == AppLifecycleState.resumed) {
            await getInstalledAppsMap();
            triggerNotify();
          }
        },
      );
    }
    super.onInitialized();
  }

  @override
  void dispose() {
    _sub?.close();
    _lifecycleListener?.dispose();
    super.dispose();
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
    final map = await getInstalledAppsMap();

    loadAppModels(Map<String, dynamic> params) async {
      final apps = await super.findAll(params: params);
      final releases = await ref.releases
          .findAll(params: {'#a': apps.map((app) => app.aTag)});
      final metadataIds = releases.map((r) => r.tagMap['e']!).expand((_) => _);
      await ref.fileMetadata.findAll(params: {
        'ids': metadataIds,
        '#m': [kAndroidMimeType]
      });
      final userIds = {
        for (final app in apps) app.signer.id,
        for (final app in apps) app.developer.id
      }.nonNulls;
      await ref.users.findAll(params: {'ids': userIds});
      return apps;
    }

    if (params!.containsKey('installed')) {
      if (map.keys.isNotEmpty) {
        params['#d'] = map.keys;
        params.remove('installed');
        print('filtering by installed ${params['#d']}');

        final apps = findAllLocal();
        if (apps.isNotEmpty) {
          loadAppModels(params);
          return apps;
        }
        return loadAppModels(params);
      }
    }

    return loadAppModels(params);
  }

  static AndroidPackageManager? _packageManager;

  Future<Map<String, String>> getInstalledAppsMap() async {
    late List<PackageInfo>? infos;
    if (Platform.isAndroid) {
      _packageManager ??= AndroidPackageManager();
      infos = await _packageManager!.getInstalledPackages();
    } else {
      infos = [];
    }

    final installedPackageInfos = infos!.where((i) => ![
          'android',
          'com.android',
          'org.chromium.webview_shell'
        ].any((e) => i.packageName!.startsWith(e)));

    return ref.read(installedAppProvider.notifier).state = {
      for (final info in installedPackageInfos)
        info.packageName!: info.versionName!
    };
  }
}

extension AppX on App {
  bool get canInstall => status == AppInstallStatus.installable;
  bool get canUpdate => status == AppInstallStatus.updatable;
  bool get isUpdated => status == AppInstallStatus.updated;

  AppInstallStatus get status {
    if (latestMetadata == null) {
      return AppInstallStatus.notInstallable;
    }
    if (installedVersion == null) {
      return AppInstallStatus.installable;
    }
    final comp = latestMetadata!.version!.compareTo(installedVersion!);
    if (comp == 1) return AppInstallStatus.updatable;
    if (comp == 0) return AppInstallStatus.updated;
    // else it's a downgrade, which is not installable
    return AppInstallStatus.notInstallable;
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
    final size = latestMetadata!.size ?? '';

    final dir = await getApplicationSupportDirectory();
    final file = File(path.join(dir.path, hash));

    installOnDevice() async {
      notifier.state = DeviceInstallProgress();

      if (await _isHashMismatch(file.path, hash)) {
        await file.delete();
        notifier.state = ErrorInstallProgress(
            Exception('Hash mismatch, aborted installation'));
        return;
      }

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
      final totalBytes = response.contentLength ?? int.tryParse(size) ?? 1;

      sub = response.stream.listen((chunk) {
        final data = Uint8List.fromList(chunk);
        sink.add(data);
        downloadedBytes += data.length;
        notifier.state =
            DownloadingInstallProgress(downloadedBytes / totalBytes, uri.host);
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
}

Future<bool> _isHashMismatch(String path, String hash) async {
  return await Isolate.run(() async {
    final bytes = await File(path).readAsBytes();
    final digest = sha256.convert(bytes);
    return digest.toString() != hash;
  });
}

// install support

enum AppInstallStatus { updated, updatable, installable, notInstallable }

// class

sealed class AppInstallProgress {}

class IdleInstallProgress extends AppInstallProgress {}

class DownloadingInstallProgress extends AppInstallProgress {
  final double progress;
  final String host;
  DownloadingInstallProgress(this.progress, this.host);
}

class DeviceInstallProgress extends AppInstallProgress {}

class ErrorInstallProgress extends AppInstallProgress {
  final Exception e;
  ErrorInstallProgress(this.e);
}

final installedAppProvider = StateProvider<Map<String, String>>((_) => {});

final installationProgressProvider =
    StateProvider.family<AppInstallProgress, String>(
        (_, arg) => IdleInstallProgress());
