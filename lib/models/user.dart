import 'dart:convert';

import 'package:collection/collection.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart' as base;
import 'package:http/http.dart' as http;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/navigation/app_initializer.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nwc.dart';

part 'user.g.dart';

@DataAdapter([NostrAdapter, UserAdapter])
class User extends base.User with DataModelMixin<User> {
  @override
  Object? get id => event.id;

  User.fromJson(super.map)
      : followers = hasMany(map['followers']),
        following = hasMany(map['following']),
        settings = belongsTo(map['settings']),
        super.fromJson();

  User.fromPubkey(String pubkey)
      : this.fromJson({
          'id': pubkey,
          'kind': 0,
          'pubkey': pubkey.hexKey,
          'created_at': DateTime.now().millisecondsSinceEpoch ~/ 1000,
          'content': '{}',
          'tags': [],
        });

  Map<String, dynamic> toJson() => super.toMap();

  @DataRelationship(inverse: 'followers')
  final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  final HasMany<User> followers;
  @DataRelationship(inverse: 'user')
  final BelongsTo<Settings> settings;

  String get nameOrNpub => name ?? npub.shorten;

  // Zaps

  Future<void> zap(int amountInSats,
      {required base.Event event, String? comment, base.Signer? signer}) async {
    final adapter = DataModel.adapterFor(this) as NostrAdapter;

    // First ensure connection is available
    final nwcConnection = adapter.ref.read(nwcConnectionProvider).asData?.value;
    if (nwcConnection == null) {
      throw 'No NWC connection';
    }

    final authorPubkey = event.event.pubkey;
    final author = adapter.ref.users.findOneLocalById(authorPubkey);

    if (author?.lud16 == null) {
      throw 'Recipient has no Lightning address';
    }

    final amountInMillisats = amountInSats * 1000;
    final partialZapRequest = base.PartialZapRequest();
    partialZapRequest
      ..addLinkedEvent(event)
      ..addLinkedUser(author!)
      ..relays = kSocialRelays.where((r) => r != 'ndk')
      ..amount = amountInMillisats
      ..comment = comment;

    final zapRequest = await partialZapRequest.signWith(signer ?? amberSigner,
        withPubkey: pubkey);

    // Now we fetch the invoice
    final lnResponse = await author.fetchLightningAddress();

    final recipientAllowsNostr = lnResponse['allowsNostr'];
    final recipientPubkey = lnResponse['nostrPubkey'];
    // All amounts are in millisats
    final recipientMin = lnResponse['minSendable'];
    final recipientMax = lnResponse['maxSendable'];

    if (recipientAllowsNostr != true && recipientPubkey != null) {
      throw 'Recipient does not allow nostr';
    }
    if ((recipientMin != null && amountInMillisats < recipientMin) ||
        (recipientMax != null && amountInMillisats > recipientMax)) {
      throw 'Amount not between min and max sendable';
    }

    final commentLength = lnResponse['commentAllowed'];
    comment =
        commentLength != null ? comment?.safeSubstring(commentLength) : comment;

    var callbackUri = Uri.parse(lnResponse['callback']!);
    callbackUri = callbackUri.replace(
      queryParameters: {
        ...callbackUri.queryParameters,
        'amount': amountInMillisats.toString(),
        if (comment != null) 'comment': comment,
        'nostr': jsonEncode(zapRequest.toMap()),
      },
    );

    final invoiceResponse = await http.get(callbackUri);
    final invoiceMap = jsonDecode(invoiceResponse.body);

    final invoice = invoiceMap['pr'];
    if (invoice == null) {
      throw 'No invoice';
    }

    // Pay the invoice via NWC (errors are thrown)
    await adapter.socialRelays.ndk!.nwc.payInvoice(nwcConnection,
        invoice: invoice, timeout: Duration(seconds: 20));
  }

  Future<Map<String, dynamic>> fetchLightningAddress() async {
    final [userName, domainName] = lud16!.split('@');
    final lnurl = 'https://$domainName/.well-known/lnurlp/$userName';
    final response = await http.get(Uri.parse(lnurl));
    final map = jsonDecode(response.body);
    return map;
  }
}

mixin UserAdapter on NostrAdapter<User> {
  final queriedAtMap = <String, DateTime>{};

  @override
  Future<List<User>> findAll(
      {bool? remote,
      bool? background,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      bool? syncLocal,
      OnSuccessAll<User>? onSuccess,
      OnErrorAll<User>? onError,
      DataRequestLabel? label}) async {
    final authors = params!['authors'] as Iterable;
    if (authors.isEmpty) {
      return [];
    }

    final request = base.RelayRequest(
      kinds: {0}, // 3
      authors: {...authors},
      since: queriedAtMap[authors.join()],
    );

    final result = await socialRelays.queryRaw(request);
    // Very rough caching
    queriedAtMap[authors.join()] = DateTime.now().subtract(Duration(hours: 1));

    if (onSuccess != null) {
      return await onSuccess.call(DataResponse(statusCode: 200, body: result),
          label ?? DataRequestLabel('findAll', type: type), this);
    }
    final data = await deserializeAsync(result, save: true);
    return data.models;
  }

  @override
  bool isOfflineError(Object? error) {
    return false;
  }

  @override
  Future<User?> findOne(Object id,
      {bool remote = true,
      bool background = false,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      OnSuccessOne<User>? onSuccess,
      OnErrorOne<User>? onError,
      DataRequestLabel? label}) async {
    if (id.toString().isEmpty) return null;

    var publicKey = id.toString();

    if (publicKey.startsWith('npub')) {
      publicKey = publicKey.hexKey;
    } else if (publicKey.contains('@')) {
      // If it's not an npub and it has a @ instead, we treat the string as NIP-05
      final [username, domain] = publicKey.split('@');
      publicKey = await sendRequest<dynamic>(
        Uri.parse('https://$domain/.well-known/nostr.json?name=$username'),
        onSuccess: (response, label) {
          var body = response.body;
          if (body is String) {
            body = jsonDecode(body);
          }
          return (body as Map)['names']?[username];
        },
        onError: (e, _) {
          throw e;
        },
      );
    }

    final result = await socialRelays.queryRaw(base.RelayRequest(
      kinds: {0}, // 3
      tags: params ?? {},
      authors: {publicKey},
    ));

    final data = await deserializeAsync(result, save: true);
    return data.models.firstWhereOrNull((e) {
      return e.id == publicKey;
    });
  }

  @override
  DeserializedData<User> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];

    for (final Map<String, dynamic> map in list) {
      map['id'] = map['pubkey'];
    }

    return super.deserialize(data);
  }

  Future<List<User>> getFollowsWhoFollow(String npub1, String npub2) async {
    final search = jsonEncode({
      'source': npub1.hexKey,
      'targets': [npub2.hexKey]
    });
    final response = await ref.users.nostrAdapter.vertexRelay
        .queryRaw(base.RelayRequest(kinds: {6312, 7000}, search: search));
    if (response.isEmpty || response.first['kind'] == 7000) {
      return [];
    }
    final result = response.first['content'];
    final pubkeyList = (jsonDecode(result) as List).map((e) => e['pubkey']);
    final users = await findAll(params: {'authors': pubkeyList});
    return users;
  }
}

final signedInUserProvider = Provider<User?>((ref) {
  return ref.settings
      .watchOne('_', alsoWatch: (_) => {_.user})
      .model
      ?.user
      .value;
});

const kZapstorePubkey =
    '78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d';

const kFranzapPubkey =
    '726a1e261cc6474674e8285e3951b3bb139be9a773d1acf49dc868db861a1c11';
