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
  final HasMany<User> trustedUsers = HasMany();
  SignInMethod? signInMethod;
}

enum SignInMethod {
  pubkey,
  nip55,
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
  }
}
