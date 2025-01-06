import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart' as base;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/extensions.dart';

part 'app_curation_set.g.dart';

@DataAdapter([NostrAdapter, AppCurationSetAdapter])
class AppCurationSet extends base.AppCurationSet
    with DataModelMixin<AppCurationSet> {
  @override
  Object get id => event.id;

  final HasMany<App> apps;
  final BelongsTo<User> signer;

  AppCurationSet.fromJson(super.map)
      : apps = hasMany(map['apps']),
        signer = belongsTo(map['signer']),
        super.fromJson();

  Map<String, dynamic> toJson() => super.toMap();

  String get name => event.content.isNotEmpty ? event.content : identifier;
}

mixin AppCurationSetAdapter on Adapter<AppCurationSet> {
  @override
  Future<List<AppCurationSet>> findAll(
      {bool? remote = true,
      bool? background = false,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      bool? syncLocal,
      OnSuccessAll<AppCurationSet>? onSuccess,
      OnErrorAll<AppCurationSet>? onError,
      DataRequestLabel? label}) async {
    final sets = await super.findAll(remote: remote!, params: params);
    final userIds = {for (final set in sets) set.signer.id}.nonNulls;
    await ref.users.findAll(params: {'authors': userIds, 'ignoreReturn': true});
    return sets;
  }

  @override
  DeserializedData<AppCurationSet> deserialize(Object? data, {String? key}) {
    final list = data is Iterable ? data : [data as Map];
    for (final Map<String, dynamic> map in list) {
      final tags = map['tags'];
      map['apps'] = base.BaseUtil.getTagSet(tags, 'a');
    }
    return super.deserialize(data);
  }
}
