import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';
import 'package:zapstore/models/user.dart';

part 'settings.g.dart';

@JsonSerializable()
@DataAdapter([SettingsAdapter])
class Settings extends DataModel<Settings> {
  @override
  String get id => '_';
  final BelongsTo<User> user = BelongsTo();
  bool isLoggedIn = false;
}

mixin SettingsAdapter on Adapter<Settings> {
  @override
  Future<void> onInitialized() async {
    // ensure it's always present
    if (!existsId('_')) {
      Settings().saveLocal();
    }
    super.onInitialized();
  }
}
