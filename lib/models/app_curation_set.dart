import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/extensions.dart';

part 'app_curation_set.g.dart';

@DataAdapter([NostrAdapter, AppCurationSetAdapter])
class AppCurationSet extends BaseAppCurationSet
    with DataModelMixin<AppCurationSet> {
  final HasMany<App> apps;
  final BelongsTo<User> signer;

  AppCurationSet.fromJson(super.map)
      : apps = hasMany(map['apps']),
        signer = belongsTo(map['signer']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  String get name => content.isNotEmpty ? content : identifier;
}

mixin AppCurationSetAdapter on Adapter<AppCurationSet> {
  @override
  DeserializedData<AppCurationSet> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final Map<String, dynamic> map in list) {
      final tagMap = tagsToMap(map['tags']);
      map['apps'] = tagMap['a'];
    }
    return super.deserialize(data);
  }
}
