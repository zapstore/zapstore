import 'package:equatable/equatable.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';

part 'app.g.dart';

@JsonSerializable()
@DataAdapter([NostrAdapter, CoolAdapter])
class App extends ZapstoreEvent<App> with BaseApp, EquatableMixin {
  late final HasMany<Release> releases;
  App();
  factory App.fromJson(Map<String, dynamic> json) => _$AppFromJson(json);
  @override
  List<Object?> get props => [id];
}

mixin CoolAdapter on Adapter<App> {
  @override
  Future<void> onInitialized() async {
    // db.execute('''
    //   CREATE TABLE IF NOT EXISTS $internalType (
    //     key INTEGER PRIMARY KEY AUTOINCREMENT,
    //     data TEXT
    //   );
    // ''');
    super.onInitialized();
  }

  Future<List<App>> query(int kind) async {
    final result = db.select(
        "SELECT data FROM $internalType WHERE json_extract(data, '\$.kind') = ?",
        [kind]);
    return deserializeFromResult(result);
  }
}
