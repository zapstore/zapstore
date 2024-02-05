import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:zapstore/adapters/nostr_adapter.dart';
import 'package:zapstore/models/base_event.dart';

part 'note.g.dart';

@JsonSerializable()
@DataRepository([NostrAdapter])
class Note extends BaseEvent<Note> {
  Note(
      {required super.id,
      required super.pubkey,
      required super.createdAt,
      required super.content,
      required super.sig,
      super.tags})
      : super(kind: 1);
}
