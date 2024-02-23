import 'package:equatable/equatable.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:ndk/ndk.dart' as ndk;

part 'user.g.dart';

abstract class BaseEvent<T extends BaseEvent<T>> = ndk.Event
    with DataModelMixin<T>;

@DataRepository([NostrAdapter, UserAdapter],
    fromJson: 'User.fromMapFactory(map)', toJson: 'model.toMap()')
class User extends BaseEvent<User> with ndk.User, EquatableMixin {
  User.fromMap(super.map) : super.fromMap();

  factory User.fromMapFactory(Map<String, dynamic> map) {
    final user = User.fromMap(map);
    user.following = HasMany<User>.fromJson(map['following']);
    return user;
  }

  @DataRelationship(inverse: 'followers')
  late final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  late final HasMany<User> followers = HasMany();

  @override
  List<Object?> get props => [id];
}

mixin UserAdapter on NostrAdapter<User> {
  @override
  Future<DeserializedData<User>> deserialize(Object? data) async {
    final users = <User>[];
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      if (map['kind'] == 3) {
        for (final [_, id, ..._] in map['tags'] as Iterable) {
          users.add(User.fromMapFactory({
            'id': id,
            'content': '',
            'pubkey': id,
            'created_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
            'kind': 0,
            'tags': [],
            'following': {'_': null},
          }));
        }
      }
    }

    for (final e in list) {
      final map = e as Map<String, dynamic>;
      if (map['kind'] == 0) {
        map['id'] = map['pubkey'];
        // TODO workaround - should not assume empty is null
        if (users.isNotEmpty) {
          map['following'] = users.map((e) => e.id).toList();
        }
        final data0 = await super.deserialize(map);
        final user = data0.model;
        if (user != null) {
          users.add(user);
        }
      }
    }

    return DeserializedData(users);
  }

  @override
  Future<User?> findOne(Object id,
      {bool? remote,
      bool? background,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      OnSuccessOne<User>? onSuccess,
      OnErrorOne<User>? onError,
      DataRequestLabel? label}) async {
    if (remote == false) {
      final key = graph.getKeyForId(internalType, id.toString());
      return localAdapter.findOne(key!);
    }

    final req = ndk.Req(
      authors: {id.toString()},
      kinds: {kind, ...?params?.remove('kinds')},
      tags: params ?? {},
    );

    print('[findOne] req $req');
    final result = await notifier.query(req);
    final data = await deserialize(result);

    for (final m in data.models) {
      print('saving model of kind ${m.kind}: ${m.content}');
      m.init().saveLocal();
    }
    return data.models.firstWhere((e) => e.id == id);
  }
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

  Map<int, String> kindType = {
    0: 'users',
    3: 'users',
    1063: 'fileMetadata',
    30063: 'releases',
  };

  @override
  Future<DeserializedData<T>> deserialize(Object? data) async {
    final list = data is Iterable ? data : [data as Map];
    final models = <T>[];
    final included = <DataModelMixin>[];

    for (final e in list) {
      final map = e as Map<String, dynamic>;
      final kind = map['kind'] as int;
      final xType = kindType[kind];
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

  int get kind =>
      kindType.entries.firstWhere((e) => e.value == internalType).key;

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
    if (remote == false) {
      return localAdapter.findAll();
    }

    final req = ndk.Req(
      kinds: {kind, ...?params?.remove('kinds')},
      tags: params ?? {},
    );

    print('req $req');
    final result = await notifier.query(req);
    final data = await deserialize(result);
    print('req result ${data.models.length}');
    for (final m in data.models) {
      print('saving model of kind ${m.kind}: ${m.content}');
      m.init().saveLocal();
    }
    return data.models;
  }
}
