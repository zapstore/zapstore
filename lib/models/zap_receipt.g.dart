// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'zap_receipt.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$ZapReceiptAdapter on Adapter<ZapReceipt> {
  static final Map<String, RelationshipMeta> _kZapReceiptRelationshipMetas = {
    'recipient': RelationshipMeta<User>(
      name: 'recipient',
      type: 'users',
      kind: 'BelongsTo',
      instance: (_) => (_ as ZapReceipt).recipient,
    ),
    'signer': RelationshipMeta<User>(
      name: 'signer',
      type: 'users',
      kind: 'BelongsTo',
      instance: (_) => (_ as ZapReceipt).signer,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kZapReceiptRelationshipMetas;

  @override
  ZapReceipt deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => ZapReceipt.fromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = model.toJson();
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _zapReceiptsFinders = <String, dynamic>{};

class $ZapReceiptAdapter = Adapter<ZapReceipt>
    with _$ZapReceiptAdapter, NostrAdapter<ZapReceipt>;

final zapReceiptsAdapterProvider = Provider<Adapter<ZapReceipt>>(
    (ref) => $ZapReceiptAdapter(ref, InternalHolder(_zapReceiptsFinders)));

extension ZapReceiptAdapterX on Adapter<ZapReceipt> {
  NostrAdapter<ZapReceipt> get nostrAdapter => this as NostrAdapter<ZapReceipt>;
}

extension ZapReceiptRelationshipGraphNodeX
    on RelationshipGraphNode<ZapReceipt> {
  RelationshipGraphNode<User> get recipient {
    final meta = _$ZapReceiptAdapter._kZapReceiptRelationshipMetas['recipient']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<User> get signer {
    final meta = _$ZapReceiptAdapter._kZapReceiptRelationshipMetas['signer']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}
