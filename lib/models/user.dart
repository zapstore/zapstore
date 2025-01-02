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

  Future<void> zap(int amountInSats,
      {required base.Event event, String? comment}) async {
    final adapter = DataModel.adapterFor(this) as NostrAdapter;

    final authorPubkey = event.event.pubkey;
    print(
        'bro wants to zap $amountInSats to $authorPubkey for ${event.event.id}');
    final author = adapter.ref.users.findOneLocalById(authorPubkey);

    if (author?.lud16 == null) {
      throw 'cant zap';
    }

    final amountInMillisats = amountInSats * 1000;
    final partialZapRequest = base.PartialZapRequest();
    partialZapRequest
      ..addLinkedEvent(event)
      ..addLinkedUser(author!)
      ..relays = kSocialRelays.where((r) => r != 'ndk')
      ..amount = amountInMillisats
      ..comment = comment;

    final zapRequest =
        await partialZapRequest.signWith(amberSigner, withPubkey: pubkey);

    // Now we fetch the invoice
    final lnurlResponse = await author.fetchLnUrl();

    final recipientAllowsNostr = lnurlResponse['allowsNostr'];
    final recipientPubkey = lnurlResponse['nostrPubkey'];
    // All amounts are in millisats
    final recipientMin = lnurlResponse['minSendable'];
    final recipientMax = lnurlResponse['maxSendable'];

    if (recipientAllowsNostr != true && recipientPubkey != null) {
      throw 'cant zap, recipient does not allow nostr';
    }
    if ((recipientMin != null && amountInMillisats < recipientMin) ||
        (recipientMax != null && amountInMillisats > recipientMax)) {
      throw 'cant zap, amount not between min and max sendable';
    }

    final commentLength = lnurlResponse['commentAllowed'];
    comment =
        commentLength != null ? comment?.substringMax(commentLength) : comment;

    var callbackUri = Uri.parse(lnurlResponse['callback']!);
    callbackUri = callbackUri.replace(
      queryParameters: {
        ...callbackUri.queryParameters,
        'amount': amountInMillisats.toString(),
        if (comment != null) 'comment': comment,
        'nostr': jsonEncode(zapRequest.toMap()),
      },
    );

    final response = await http.get(callbackUri);
    final invoiceMap = jsonDecode(response.body);

    final invoice = invoiceMap['pr'];
    if (invoice == null) {
      throw 'cant zap, no invoice';
    }

    final nwcConnection = adapter.ref.read(nwcConnectionProvider).asData?.value;
    if (nwcConnection == null) {
      throw 'cant zap, no nwc connection';
    }

    // Pay the invoice via NWC
    final rw = await adapter.socialRelays.ndk!.nwc.payInvoice(nwcConnection,
        invoice: invoice, timeout: Duration(seconds: 20));
    print(rw);
  }

  Future<Map<String, dynamic>> fetchLnUrl() async {
    final [userName, domainName] = lud16!.split('@');
    final lnurl = 'https://$domainName/.well-known/lnurlp/$userName';
    final response = await http.get(Uri.parse(lnurl));
    final map = jsonDecode(response.body);
    return map;
  }

  User.fromJson(super.map)
      : followers = hasMany(map['followers']),
        following = hasMany(map['following']),
        settings = belongsTo(map['settings']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  @DataRelationship(inverse: 'followers')
  final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  final HasMany<User> followers;
  @DataRelationship(inverse: 'user')
  final BelongsTo<Settings> settings;

  String get nameOrNpub => name ?? npub.substringMax(18);
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
    } else {
      // If it's not an npub we treat the string as NIP-05
      if (!publicKey.contains('@')) {
        // If it does not have a @, we treat the string as a domain name
        publicKey = '_@$publicKey';
      }

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

  Future<List<User>> getTrusted(String npub1, String npub2) async {
    final url = 'https://trustgraph.live/api/fwf/$npub1/$npub2';
    final users = await sendRequest(
      Uri.parse(url),
      onSuccess: (response, label) async {
        if (response.body == null) return null;
        final map =
            Map<String, dynamic>.from(jsonDecode(response.body.toString()));

        final trustedKeys = map.keys.map((npub) => npub.hexKey);
        return await findAll(
          params: {'authors': trustedKeys},
          onSuccess: (response, label, adapter) {
            final data = deserialize(response.body);
            return data.models;
          },
        );
      },
    );
    return users!;
  }
}

const kZapstorePubkey =
    '78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d';

const kFranzapPubkey =
    '726a1e261cc6474674e8285e3951b3bb139be9a773d1acf49dc868db861a1c11';
