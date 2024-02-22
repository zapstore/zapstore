import 'dart:io';
import 'dart:math';

import 'package:android_package_installer/android_package_installer.dart';
import 'package:crypto/crypto.dart';
import 'package:expansion_tile_card/expansion_tile_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/screens/profile_screen.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as path;
import 'package:permission_handler/permission_handler.dart';

import '../main.data.dart';

class SearchScreen extends HookConsumerWidget {
  SearchScreen({super.key});
  final r = Random();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchState = useState<String?>(null);
    final labelState = useState<DataRequestLabel?>(null);

    final value = ref.releases.watchAll(remote: false);

    return Padding(
      padding: const EdgeInsets.all(12.0),
      child: Column(
        children: [
          TextButton(
              onPressed: () async {
                var status = await Permission.storage.status;
                if (!status.isGranted) {
                  // If not we will ask for permission first
                  await Permission.storage.request();
                }
                final apk = await rootBundle.load('tmp/gg.apk');
                final dir = await getApplicationDocumentsDirectory();
                await dir.create(recursive: true);
                final buffer = apk.buffer;
                await File('${dir.path}/gg.apk').writeAsBytes(
                    buffer.asUint8List(apk.offsetInBytes, apk.lengthInBytes));

                int? code = await AndroidPackageInstaller.installApk(
                    apkFilePath: '${dir.path}/gg.apk');
                if (code != null) {
                  PackageInstallerStatus installationStatus =
                      PackageInstallerStatus.byCode(code);
                  print(installationStatus.name);
                }
              },
              child: Text('install')),
          SearchBar(
            elevation: MaterialStatePropertyAll(2.2),
            onSubmitted: (query) async {
              searchState.value = query;
              labelState.value = DataRequestLabel('query',
                  type: 'releases', requestId: 'sub-${r.nextInt(9999999)}');

              await ref.releases.findAll();
              await ref.fileMetadata.findAll(
                params: {
                  // 'kinds': {1063},
                  // 'limit': 20,
                  // 'search': query,
                  '#m': [
                    // 'application/pwa+zip',
                    'application/vnd.android.package-archive'
                  ],
                  // 'since': DateTime.now()
                  //         .subtract(Duration(days: 1))
                  //         .millisecondsSinceEpoch ~/
                  //     1000,
                },
                label: labelState.value,
              );

              // final notifier = ref.read(frameProvider.notifier);
              // notifier.send(jsonEncode([
              //   "REQ",
              //   subscriptionIdState.value!,
              // ]));
            },
          ),
          Padding(
            padding: const EdgeInsets.all(6.0),
            child: Text('Total results: ${value.model.length}'),
          ),
          Expanded(
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: value.model.length,
              itemBuilder: (context, index) {
                final event = value.model[index];
                return GestureDetector(
                  onTap: () {
                    context.push('/search/details', extra: event);
                  },
                  child: Card(
                    child: Text(
                        '${event.content} / ${event.id} / ${event.kind} / ${event.artifacts.keys} - ${event.runtimeType}'),
                  ),
                );
                // return CardWidget(note: event as FileMetadata);
              },
            ),
          ),
        ],
      ),
    );
  }
}

class CardWidget extends HookConsumerWidget {
  final FileMetadata fm;

  const CardWidget({super.key, required this.fm});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authorUser = useState<User?>(null);
    final currentUser = ref.read(loggedInUser);

    final isWebOfTrust =
        currentUser?.following.contains(authorUser.value) ?? false;
    final isSha256Ok = useState<bool?>(null);

    return ExpansionTileCard(
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(15.0),
        child: Image.network(
          fm.tagMap['thumb']!.first,
          width: 80,
          height: 80,
          fit: BoxFit.cover,
          errorBuilder: (context, err, _) => Text(err.toString()),
        ),
      ),
      title: Text(
        fm.content,
        overflow: TextOverflow.ellipsis,
        maxLines: 1,
        style: TextStyle(fontWeight: FontWeight.bold, fontSize: 20),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Type: ${fm.mimeType} (${fm.id})'),
        ],
      ),
      onExpansionChanged: (isOpen) async {
        if (isOpen && currentUser != null) {
          // final existingUser =
          //     await ref.users.findOne(note.pubkey, remote: false);
          // if (existingUser?.name != null) {
          //   print('fd has ${note.pubkey}! (${existingUser!.name}) ');
          //   authorUser.value = existingUser;
          // } else {
          //   print('fetch in network');
          //   authorUser.value =
          //       await ref.read(profileProvider((note.pubkey, false)).future);
          // }
        }
      },
      children: [
        Align(
          alignment: Alignment.centerLeft,
          child: Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: 16.0,
              vertical: 8.0,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // if (authorUser.value != null)
                //   Text(
                //       'Author: ${authorUser.value!.name} (${authorUser.value!.nip05})'),
                if (isWebOfTrust)
                  Text(
                    'Author is in your web of trust',
                    style: TextStyle(color: Colors.green),
                  ),
                if (!isWebOfTrust)
                  Text('Author is not in your web of trust',
                      style: TextStyle(color: Colors.red)),
                if (isSha256Ok.value == true)
                  Text(
                    'Installed! Hash and signature matched',
                    style: TextStyle(color: Colors.green),
                  ),
                if (isSha256Ok.value == false)
                  Text(
                    'Removed! Hash or signature did not match',
                    style: TextStyle(color: Colors.red),
                  ),
                SizedBox(height: 20),
                if (isSha256Ok.value != false)
                  ElevatedButton.icon(
                    label: Text('Install'),
                    icon: Icon(Icons.arrow_downward),
                    onPressed: () async {
                      final uri = Uri.parse(fm.tagMap['url']!.first);
                      final response = await http.get(uri);
                      final dir = ref.read(downloadsPath);

                      File file =
                          File(path.join(dir, fm.tagMap['name']!.first));
                      await file.writeAsBytes(response.bodyBytes);

                      final digest = sha256.convert(response.bodyBytes);
                      isSha256Ok.value =
                          digest.toString() == fm.tagMap['x']!.first;

                      if (isSha256Ok.value == false) {
                        print('actual digest $digest');
                        await file.delete();
                      } else {
                        print('digest ok $digest');
                      }
                    },
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
