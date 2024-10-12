// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'app.dart';

// **************************************************************************
// AdapterGenerator
// **************************************************************************

// ignore_for_file: non_constant_identifier_names, duplicate_ignore

mixin _$AppAdapter on Adapter<App> {
  static final Map<String, RelationshipMeta> _kAppRelationshipMetas = {
    'releases': RelationshipMeta<Release>(
      name: 'releases',
      inverseName: 'app',
      type: 'releases',
      kind: 'HasMany',
      instance: (_) => (_ as App).releases,
    ),
    'signer': RelationshipMeta<User>(
      name: 'signer',
      type: 'users',
      kind: 'BelongsTo',
      instance: (_) => (_ as App).signer,
    ),
    'developer': RelationshipMeta<User>(
      name: 'developer',
      type: 'users',
      kind: 'BelongsTo',
      instance: (_) => (_ as App).developer,
    ),
    'localApp': RelationshipMeta<LocalApp>(
      name: 'localApp',
      type: 'localApps',
      kind: 'BelongsTo',
      instance: (_) => (_ as App).localApp,
    )
  };

  @override
  Map<String, RelationshipMeta> get relationshipMetas => _kAppRelationshipMetas;

  @override
  App deserializeLocal(map, {String? key}) {
    map = transformDeserialize(map);
    return internalWrapStopInit(() => App.fromJson(map), key: key);
  }

  @override
  Map<String, dynamic> serializeLocal(model, {bool withRelationships = true}) {
    final map = model.toJson();
    return transformSerialize(map, withRelationships: withRelationships);
  }
}

final _appsFinders = <String, dynamic>{};

class $AppAdapter = Adapter<App>
    with _$AppAdapter, NostrAdapter<App>, AppAdapter;

final appsAdapterProvider = Provider<Adapter<App>>(
    (ref) => $AppAdapter(ref, InternalHolder(_appsFinders)));

extension AppAdapterX on Adapter<App> {
  NostrAdapter<App> get nostrAdapter => this as NostrAdapter<App>;
  AppAdapter get appAdapter => this as AppAdapter;
}

extension AppRelationshipGraphNodeX on RelationshipGraphNode<App> {
  RelationshipGraphNode<Release> get releases {
    final meta = _$AppAdapter._kAppRelationshipMetas['releases']
        as RelationshipMeta<Release>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<User> get signer {
    final meta =
        _$AppAdapter._kAppRelationshipMetas['signer'] as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<User> get developer {
    final meta = _$AppAdapter._kAppRelationshipMetas['developer']
        as RelationshipMeta<User>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }

  RelationshipGraphNode<LocalApp> get localApp {
    final meta = _$AppAdapter._kAppRelationshipMetas['localApp']
        as RelationshipMeta<LocalApp>;
    return meta.clone(
        parent: this is RelationshipMeta ? this as RelationshipMeta : null);
  }
}
