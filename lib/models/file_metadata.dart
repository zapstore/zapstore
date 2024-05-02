import 'dart:io';

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

part 'file_metadata.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, FileMetadataAdapter])
class FileMetadata extends ZapstoreEvent<FileMetadata> with BaseFileMetadata {
  late final BelongsTo<User> author;
  late final BelongsTo<Release> release = BelongsTo();
  late final BelongsTo<User> signer;

  Future<void> install() async {
    // throw Exception('something nasty');
    var status = await Permission.storage.status;
    if (!status.isGranted) {
      // If not we will ask for permission first
      await Permission.storage.request();
    }

    // download file

    final uri = Uri.parse(url);
    final response = await http.get(uri);
    final dir = await getApplicationDocumentsDirectory();
    final file = File(path.join(dir.path, path.basename(url)));
    await file.writeAsBytes(response.bodyBytes);

    // check hash

    final digest = sha256.convert(response.bodyBytes);
    final shaOk = digest.toString() == hash;

    if (!shaOk) {
      throw Exception('sha not ok');
    }

    int? code =
        await AndroidPackageInstaller.installApk(apkFilePath: file.path);
    if (code != 0) {
      throw Exception(
          'installation error: ${code != null ? PackageInstallerStatus.byCode(code) : ''}');
    }
    // final apps = await packageManager.getInstalledPackages();
    // apps?.firstWhere((e) => e.packageName == release.value!.identifier);
  }
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
