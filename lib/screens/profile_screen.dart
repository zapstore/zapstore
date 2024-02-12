import 'dart:async';
import 'dart:convert';

import 'package:bech32/bech32.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:ndk/ndk.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/user.dart';
import 'package:convert/convert.dart';

final profileProvider =
    FutureProvider.family<User, (String, bool)>((ref, record) async {
  final (pubkey, loadContacts) = record;
  final completer = Completer<User>();
  final notifier = ref.read(frameProvider.notifier);
  print('profile provider sending req');
  notifier.send(jsonEncode([
    "REQ",
    pubkey,
    {
      'authors': [pubkey],
      'kinds': [0, 3],
    }
  ]));

  late Function _sub;

  User? u;
  List<String>? contacts;
  _sub = ref.watch(frameProvider.notifier).addListener((frame) async {
    print('listener: ${frame.event}');
    final event = frame.event;

    if (event is Metadata) {
      u = (await ref.users.findOne(event.pubkey, remote: false)) ??
          User(id: event.pubkey);
      final map = jsonDecode(event.content);
      u!.name = map['displayName'] ?? map['display_name'] ?? map['name'];
      u!.nip05 = map['nip05'];
    }

    if (loadContacts == false && u != null && completer.isCompleted == false) {
      completer.complete(u);
      return;
    }

    if (event is ContactList) {
      contacts = [...?contacts, ...event.tagMap['p']!];
    }

    if (u != null && contacts != null) {
      if (completer.isCompleted == false) {
        print('completing with $u');
        contacts!.forEach((c) {
          final uc = User(id: c);
          u!.following.add(uc);
          uc.saveLocal();
        });
        u!.saveLocal();
        completer.complete(u);
      }
    }
  });
  ref.onDispose(() {
    print('disposing');
    _sub();
  });
  return completer.future;
});

final loggedInUser = StateProvider<User?>((ref) => null);
final downloadsPath = StateProvider<String>((ref) => '/tmp');

class ProfileScreen extends HookConsumerWidget {
  ProfileScreen({super.key});
  final codec = Bech32Codec();
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final u2 = ref.watch(loggedInUser);
    final controller = useTextEditingController();
    final controller2 = useTextEditingController(text: ref.read(downloadsPath));

    return Expanded(
      child: Padding(
        padding: const EdgeInsets.all(12.0),
        child: Column(
          children: [
            TextField(controller: controller),
            SizedBox(height: 10),
            ElevatedButton(
                onPressed: () async {
                  final pubkey = bech32Decode(controller.text);
                  final uid =
                      await ref.read(profileProvider((pubkey, true)).future);
                  ref.read(loggedInUser.notifier).state =
                      await ref.users.findOne(uid, remote: false);
                },
                child: Text('Log in with npub')),
            if (u2 != null)
              Expanded(
                child: Column(
                  children: [
                    Text('Logged in as ${u2.name}'),
                    Text('(following: ${u2.following.length})'),
                  ],
                ),
              ),
            SizedBox(height: 20),
            // ListView.builder(
            //     shrinkWrap: true,
            //     itemCount: contacts.length,
            //     itemBuilder: (context, i) {
            //       return Text(' - ${contacts[i].id}');
            //     }),
            TextField(controller: controller2),
            SizedBox(height: 10),
            ElevatedButton(
              onPressed: () async {
                ref.read(downloadsPath.notifier).state = controller2.text;
              },
              child: Text('Save downloads path'),
            ),
          ],
        ),
      ),
    );
  }
}

String bech32Encode(String prefix, String hexData) {
  final data = hex.decode(hexData);
  final convertedData = convertBits(data, 8, 5, true);
  final bech32Data = Bech32(prefix, convertedData);
  return bech32.encode(bech32Data);
}

String bech32Decode(String bech32Data) {
  final decodedData = bech32.decode(bech32Data);
  final convertedData = convertBits(decodedData.data, 5, 8, false);
  final hexData = hex.encode(convertedData);

  return hexData;
}

List<int> convertBits(List<int> data, int fromBits, int toBits, bool pad) {
  var acc = 0;
  var bits = 0;
  final maxv = (1 << toBits) - 1;
  final result = <int>[];

  for (final value in data) {
    if (value < 0 || value >> fromBits != 0) {
      throw Exception('Invalid value: $value');
    }
    acc = (acc << fromBits) | value;
    bits += fromBits;

    while (bits >= toBits) {
      bits -= toBits;
      result.add((acc >> bits) & maxv);
    }
  }

  if (pad) {
    if (bits > 0) {
      result.add((acc << (toBits - bits)) & maxv);
    }
  } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
    throw Exception('Invalid data');
  }

  return result;
}
