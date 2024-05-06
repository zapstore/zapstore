import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:android_package_installer/android_package_installer.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as path;
import 'package:zapstore/screens/app_detail_screen.dart';

part 'file_metadata.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, FileMetadataAdapter])
class FileMetadata extends Event<FileMetadata> with BaseFileMetadata {
  late final BelongsTo<User> author;
  late final BelongsTo<Release> release = BelongsTo();
  late final BelongsTo<User> signer;

  Future<void> install() async {
    final notifier = DataModel.adapterFor(this)
        .ref
        .read(installationProgressProvider(id!.toString()).notifier);

    final installPermission = await Permission.requestInstallPackages.status;
    if (!installPermission.isGranted) {
      final newStatus = await Permission.requestInstallPackages.request();
      if (newStatus.isDenied) {
        throw Exception('Installation permission denied');
      }
    }

    final dir = await getApplicationSupportDirectory();
    final file = File(path.join(dir.path, hash));

    installOnDevice() async {
      notifier.state = DeviceInstallProgress();

      if (await _isHashMismatch(file.path, hash!)) {
        notifier.state = FinishedInstallProgress(
            e: Exception('Hash mismatch, aborted installation'));
        return;
      }

      int? code =
          await AndroidPackageInstaller.installApk(apkFilePath: file.path);
      if (code != 0) {
        notifier.state = FinishedInstallProgress(
            e: Exception(
                'Install: ${code != null ? PackageInstallerStatus.byCode(code) : ''}'));
      }

      await file.delete();
      notifier.state = FinishedInstallProgress();
      // TODO should recalculate installedVersion and save app
    }

    if (await file.exists()) {
      await installOnDevice();
    } else {
      final client = http.Client();
      final sink = file.openWrite();

      final backupUrl = 'https://cdn.zap.store/$hash';
      final url = urls.firstOrNull ?? backupUrl;
      var downloadedBytes = 0;

      var response = await client.send(http.Request('GET', Uri.parse(url)));
      if (response.statusCode != 200) {
        final uri = Uri.parse(backupUrl);
        response = await client.send(http.Request('GET', uri));
      }
      final totalBytes =
          response.contentLength ?? int.tryParse(size ?? '1') ?? 1;

      response.stream.listen((chunk) {
        final data = Uint8List.fromList(chunk);
        sink.add(data);
        downloadedBytes += data.length;
        notifier.state =
            DownloadingInstallProgress(downloadedBytes / totalBytes);
      }, onError: (e) {
        notifier.state = FinishedInstallProgress(e: e);
      }, onDone: () async {
        await sink.close();
        client.close();
        await installOnDevice();
      });
    }
  }

  static Future<bool> _isHashMismatch(String path, String hash) async =>
      await Isolate.run(() async {
        final bytes = await File(path).readAsBytes();
        final digest = sha256.convert(bytes);
        return digest.toString() != hash;
      });
}

mixin FileMetadataAdapter on Adapter<FileMetadata> {
  @override
  DeserializedData<FileMetadata> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      map['author'] = map['pubkey'];
    }
    return super.deserialize(data);
  }
}
