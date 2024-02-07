import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:desktop_drop/desktop_drop.dart';
import 'package:expansion_tile_card/expansion_tile_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:ndk/ndk.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/screens/profile_screen.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as path;

class SearchScreen extends HookConsumerWidget {
  SearchScreen({super.key});
  final r = Random();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchState = useState<String?>(null);
    final subscriptionIdState = useState<String?>(null);
    final value =
        ref.watch(subscriptionFrameProvider(subscriptionIdState.value));

    return Expanded(
      child: DropTarget(
        onDragDone: (detail) async {
          if (detail.files.isNotEmpty) {
            final bytes = await detail.files.first.readAsBytes();
            final digest = sha256.convert(bytes).toString();

            subscriptionIdState.value = 'sub-${digest}';
            final notifier = ref.read(frameProvider.notifier);
            notifier.send(jsonEncode([
              "REQ",
              subscriptionIdState.value!,
              {
                'kinds': [1063],
                '#x': [digest],
              }
            ]));
          }
          // print(detail.files.map((e) => e.path));
        },
        child: Padding(
          padding: const EdgeInsets.all(12.0),
          child: Column(
            children: [
              SearchBar(
                elevation: MaterialStatePropertyAll(2.2),
                onSubmitted: (query) {
                  searchState.value = query;
                  subscriptionIdState.value = 'sub-${r.nextInt(9999999)}';
                  final notifier = ref.read(frameProvider.notifier);
                  notifier.send(jsonEncode([
                    "REQ",
                    subscriptionIdState.value!,
                    {
                      'kinds': [1063],
                      'limit': 20,
                      'search': query,
                      // '#m': ['video/mp4'],
                      // 'since': DateTime.now()
                      //         .subtract(Duration(days: 1))
                      //         .millisecondsSinceEpoch ~/
                      //     1000,
                    }
                  ]));
                },
              ),
              Padding(
                padding: const EdgeInsets.all(6.0),
                child: Text('Total results: ${value.length}'),
              ),
              Expanded(
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: value.length,
                  itemBuilder: (context, index) {
                    final event = value[index];
                    return CardWidget(note: event as FileMetadata);
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class CardWidget extends HookConsumerWidget {
  final FileMetadata note;

  const CardWidget({super.key, required this.note});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authorUser = useState<User?>(null);
    final currentUser = ref.read(loggedInUser);

    final isWebOfTrust =
        currentUser?.following.contains(authorUser.value) ?? false;
    final isSha256Ok = useState<bool?>(null);

    return ExpansionTileCard(
      // key: cardA,
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(15.0),
        child: Image.network(
          note.tagMap['url']!.first,
          width: 80,
          height: 80,
          fit: BoxFit.cover,
          errorBuilder: (context, err, _) => Text(err.toString()),
        ),
      ),
      title: Text(
        note.content,
        overflow: TextOverflow.ellipsis,
        maxLines: 1,
        style: TextStyle(fontWeight: FontWeight.bold, fontSize: 20),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Type: ${note.tagMap['m']?.first}'),
        ],
      ),
      onExpansionChanged: (isOpen) async {
        if (isOpen && currentUser != null) {
          final existingUser =
              await ref.users.findOne(note.pubkey, remote: false);
          if (existingUser?.name != null) {
            print('fd has ${note.pubkey}! (${existingUser!.name}) ');
            authorUser.value = existingUser;
          } else {
            authorUser.value =
                await ref.read(profileProvider(note.pubkey).future);
          }
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
                if (authorUser.value != null)
                  Text(
                      'Author: ${authorUser.value!.name} (${authorUser.value!.nip05})'),
                if (isWebOfTrust)
                  Text(
                    'Author is in your web of trust',
                    style: TextStyle(color: Colors.green),
                  ),
                if (!isWebOfTrust)
                  Text('Author is not in your web of trust',
                      style: TextStyle(color: Colors.red)),
                Text(
                  'SHA-256: ${note.tagMap['x']?.first}',
                  style: TextStyle(
                      color: (isSha256Ok.value != null)
                          ? (isSha256Ok.value! ? Colors.green : Colors.red)
                          : Colors.white),
                ),
                TextButton(
                  child: const Column(
                    children: <Widget>[
                      Text('Install'),
                      Icon(Icons.arrow_downward),
                    ],
                  ),
                  onPressed: () async {
                    final uri = Uri.parse(note.tagMap['url']!.first);
                    final response = await http.get(uri);
                    final dir = ref.read(downloadsPath);
                    File file = File(path.join(dir, uri.pathSegments.last));
                    await file.writeAsBytes(response.bodyBytes);
                    final digest = sha256.convert(response.bodyBytes);
                    isSha256Ok.value =
                        digest.toString() == note.tagMap['x']!.first;
                    if (isSha256Ok.value == false) {
                      print('actual digest $digest');
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
