import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';

part 'app.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter])
class App extends ZapstoreEvent<App> with BaseApp {
  late final HasMany<Release> releases;
  late final BelongsTo<User> signer;
  late final BelongsTo<User> developer;
}
