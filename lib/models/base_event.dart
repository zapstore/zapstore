import 'package:collection/collection.dart';
import 'package:dart_nostr/dart_nostr.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';

abstract class BaseEvent<T extends BaseEvent<T>> extends DataModel<T> {
  @override
  final String id;

  final NostrEvent _event;
  String get pubkey => _event.pubkey;
  int get kind => _event.kind;
  String get content => _event.content;
  String get sig => _event.sig;
  @JsonKey(name: 'created_at')
  DateTime get createdAt => _event.createdAt;
  List<List<String>> get tags => _event.tags;

  Map<String, List<String>> get tagMap {
    final f2 = _event.tags.groupFoldBy<String, List<String>>(
        (e) => e.first, (acc, e) => [...?acc, e[1]]);
    return f2;
  }

  BaseEvent(
      {required this.id,
      required String pubkey,
      required DateTime createdAt,
      required int kind,
      List<List<String>> tags = const [],
      required String content,
      required String sig})
      : _event = NostrEvent(
          id: id,
          pubkey: pubkey,
          createdAt: createdAt,
          kind: kind,
          tags: tags,
          content: content,
          sig: sig,
          ots: null,
        );
}
