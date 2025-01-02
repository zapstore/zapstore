import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart' as base;
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/extensions.dart';

part 'zap_receipt.g.dart';

@DataAdapter([NostrAdapter, ZapReceiptAdapter])
class ZapReceipt extends base.ZapReceipt with DataModelMixin<ZapReceipt> {
  final BelongsTo<User> recipient;
  final BelongsTo<User> signer;

  @override
  Object? get id => event.id;

  ZapReceipt.fromJson(super.map)
      : recipient = belongsTo(map['recipient']),
        signer = belongsTo(map['signer']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();
}

mixin ZapReceiptAdapter on Adapter<ZapReceipt> {
  @override
  DeserializedData<ZapReceipt> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final e in list) {
      final map = e as Map<String, dynamic>;
      map['recipient'] = base.BaseUtil.getTagSet(map['tags'], 'p');
    }
    return super.deserialize(data);
  }

  List<ZapReceipt> findByRecipient(
      {required String pubkey, required String eventId}) {
    // NOTE: This query is technically incorrect using IN, but the probability of clashing p/e IDs is extremely low
    // This will be fixed with the new purplebase
    final result = db.select(
        "SELECT z.key, data from zapReceipts z, json_each(json_extract(data, '\$.tags')) WHERE json_extract(value, '\$[0]') in ('p', 'e') and json_extract(value, '\$[1]') in (?, ?)",
        [pubkey, eventId]);
    return deserializeFromResult(result);
  }
}
