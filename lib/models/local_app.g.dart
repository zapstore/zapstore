// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'local_app.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$LocalAppAdapter on Adapter<LocalApp> {
  static final Map<String, RelationshipMeta> _kLocalAppRelationshipMetas = {};

  @override
  Map<String, RelationshipMeta> get relationshipMetas =>
      _kLocalAppRelationshipMetas;

  @override
  LocalApp deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => _$LocalAppFromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = _$LocalAppToJson(model);
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _localAppsFinders = <String, dynamic>{};

class $LocalAppAdapter = Adapter<LocalApp>
    with _$LocalAppAdapter, LocalAppAdapter;

final localAppsAdapterProvider = Provider<Adapter<LocalApp>>(
    (ref) => $LocalAppAdapter(ref, InternalHolder(_localAppsFinders)));

extension LocalAppAdapterX on Adapter<LocalApp> {
  LocalAppAdapter get localAppAdapter => this as LocalAppAdapter;
}

extension LocalAppRelationshipGraphNodeX on RelationshipGraphNode<LocalApp> {}

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

LocalApp _$LocalAppFromJson(Map<String, dynamic> json) => LocalApp(
      id: json['id'],
      installedVersion: json['installedVersion'] as String?,
      installedVersionCode: (json['installedVersionCode'] as num?)?.toInt(),
      status: $enumDecodeNullable(_$AppInstallStatusEnumMap, json['status']),
      disabled: json['disabled'] as bool? ?? false,
    );

Map<String, dynamic> _$LocalAppToJson(LocalApp instance) => <String, dynamic>{
      'id': instance.id,
      'installedVersion': instance.installedVersion,
      'installedVersionCode': instance.installedVersionCode,
      'status': _$AppInstallStatusEnumMap[instance.status],
      'disabled': instance.disabled,
    };

const _$AppInstallStatusEnumMap = {
  AppInstallStatus.updated: 'updated',
  AppInstallStatus.updatable: 'updatable',
  AppInstallStatus.downgrade: 'downgrade',
};
