import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/models/user.dart';

part 'settings.g.dart';

@JsonSerializable()
@DataAdapter([SettingsAdapter])
class Settings extends DataModel<Settings> {
  @override
  String get id => '_';
  final BelongsTo<User> user = BelongsTo();
  bool isLoggedIn = false;
  @JsonKey(defaultValue: 1)
  int dbVersion = kDbVersion;
}

mixin SettingsAdapter on Adapter<Settings> {
  @override
  Future<void> onInitialized() async {
    await super.onInitialized();
    if (inIsolate) {
      return;
    }
    // ensure it's always present
    if (!existsId('_')) {
      Settings().saveLocal();
    }
    print('initing and db version is ${findOneLocalById('_')!.dbVersion} ');
  }
}
