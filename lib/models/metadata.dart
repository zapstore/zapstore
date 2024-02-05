import 'package:dart_nostr/dart_nostr.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:zapstore/adapters/nostr_adapter.dart';
import 'package:zapstore/models/base_event.dart';

part 'metadata.g.dart';

@JsonSerializable()
@DataRepository([NostrAdapter])
class FileMetadata extends BaseEvent<FileMetadata> {
  String get url => tagMap['url']!.first;
  String get mimeType => tagMap['m']!.first;
  String get sha256 => tagMap['x']!.first;

  FileMetadata(
      {required super.id,
      required super.pubkey,
      required super.createdAt,
      required super.content,
      required super.sig,
      super.tags})
      : super(kind: 1063);
}

class FileMetadataFilter extends NostrFilter {
  final List<String>? m;
  FileMetadataFilter(
      {super.ids,
      super.authors,
      super.e,
      super.p,
      this.m,
      super.since,
      super.until,
      super.limit,
      super.search})
      : super(kinds: [1063]);

  @override
  Map<String, dynamic> toMap() {
    return {'#m': m, ...super.toMap()};
  }
}
