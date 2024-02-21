import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/ndk.dart' as ndk;

part 'user.g.dart';

abstract class BaseEvent<T extends BaseEvent<T>> = ndk.Event
    with DataModelMixin<T>;

@DataRepository([NostrAdapter],
    fromJson: 'User.fromMapFactory(map)', toJson: 'model.toMap()')
class User extends BaseEvent<User> with ndk.User {
  User.fromMap(super.map) : super.fromMap();

  factory User.fromMapFactory(Map<String, dynamic> map) {
    final user = User.fromMap(map);
    user.following = HasMany<User>.fromJson({});
    return user;
  }

  @DataRelationship(inverse: 'followers')
  late final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  late final HasMany<User> followers = HasMany();
}

mixin NostrAdapter<T extends BaseEvent<T>> on RemoteAdapter<T> {
  // void Function()? _closeSub;
  late final ndk.FrameNotifier notifier;

  @override
  Future<void> onInitialized() async {
    notifier = ref.read(ndk.frameProvider.notifier);
    // _closeSub = notifier.addListener((state) async {
    //   if (state is ndk.EventFrame &&
    //       typeKind[internalType] == state.event['kind']) {
    //     final data = await deserialize(state.event);
    //     for (final m in [...data.models, ...data.included]) {
    //       print(
    //           'r deserialize/save (k:${(m as BaseEvent).kind}) ${m.id} for type $internalType');
    //       // DataModel.adapterFor(m).saveLocal(m.init());
    //     }
    //   }
    // });
    super.onInitialized();
  }

  // @override
  // void dispose() {
  //   _closeSub?.call();
  //   super.dispose();
  // }

  Map<String, int> typeKind = {
    'fileMetadata': 1063,
    'releases': 30063,
  };

  @override
  Future<DeserializedData<T>> deserialize(Object? data) async {
    final list = data is Iterable ? data : [data as Map];
    final models = <T>[];
    final included = <DataModelMixin>[];

    for (final e in list) {
      final map = e as Map<String, dynamic>;
      final kind = map['kind'] as int;
      final xType =
          typeKind.entries.firstWhereOrNull((e) => e.value == kind)?.key;
      if (xType != null) {
        if (xType == internalType) {
          final newData = await super.deserialize(map);
          models.addAll(newData.models as Iterable<T>);
        } else {
          final newData = await adapters[xType]!.deserialize(map);
          included.addAll(newData.models as Iterable<DataModelMixin>);
        }
      }
    }
    return DeserializedData<T>(models, included: included);
  }

  @override
  Future<List<T>> findAll(
      {bool? remote,
      bool? background,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      bool? syncLocal,
      OnSuccessAll<T>? onSuccess,
      OnErrorAll<T>? onError,
      DataRequestLabel? label}) async {
    final req = ndk.Req(
      kinds: {typeKind[internalType] as int, ...?params?.remove('kinds')},
      tags: params ?? {},
    );
    if (remote == false) {
      return localAdapter.findAll();
    }

    print('req $req');
    final result = await notifier.query(req);
    final data = await deserialize(result);
    print('req result ${data.models.length}');
    for (final m in data.models) {
      m.init().saveLocal();
    }
    return data.models;
  }
}

// final profileProvider =
//     FutureProvider.family<User, (String, bool)>((ref, record) async {
//   final (pubkey, loadContacts) = record;
//   final completer = Completer<User>();
//   final notifier = ref.read(frameProvider.notifier);
//   print('profile provider sending req');
//   notifier.send(jsonEncode([
//     "REQ",
//     pubkey,
//     {
//       'authors': [pubkey],
//       'kinds': [0, 3],
//     }
//   ]));

//   late Function _sub;

//   User? u;
//   List<String>? contacts;
//   _sub = ref.watch(frameProvider.notifier).addListener((frame) async {
//     print('listener: ${frame.event}');
//     final event = frame.event;

//     if (event is Metadata) {
//       u = (await ref.users.findOne(event.pubkey, remote: false)) ??
//           User(id: event.pubkey);
//       final map = jsonDecode(event.content);
//       u!.name = map['displayName'] ?? map['display_name'] ?? map['name'];
//       u!.nip05 = map['nip05'];
//     }

//     if (loadContacts == false && u != null && completer.isCompleted == false) {
//       completer.complete(u);
//       return;
//     }

//     if (event is ContactList) {
//       contacts = [...?contacts, ...event.tagMap['p']!];
//     }

//     if (u != null && contacts != null) {
//       if (completer.isCompleted == false) {
//         print('completing with $u');
//         contacts!.forEach((c) {
//           final uc = User(id: c);
//           u!.following.add(uc);
//           uc.saveLocal();
//         });
//         u!.saveLocal();
//         completer.complete(u);
//       }
//     }
//   });
//   ref.onDispose(() {
//     print('disposing');
//     _sub();
//   });
//   return completer.future;
// });