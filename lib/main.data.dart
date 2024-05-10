

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: directives_ordering, top_level_function_literal_block, depend_on_referenced_packages

import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';

final adapterProvidersMap = <String, Provider<Adapter<DataModelMixin>>>{
  'apps': appsAdapterProvider,
'fileMetadata': fileMetadataAdapterProvider,
'releases': releasesAdapterProvider,
'settings': settingsAdapterProvider,
'users': usersAdapterProvider
};

extension AdapterWidgetRefX on WidgetRef {
  Adapter<App> get apps => watch(appsAdapterProvider)..internalWatch = watch;
  Adapter<FileMetadata> get fileMetadata => watch(fileMetadataAdapterProvider)..internalWatch = watch;
  Adapter<Release> get releases => watch(releasesAdapterProvider)..internalWatch = watch;
  Adapter<Settings> get settings => watch(settingsAdapterProvider)..internalWatch = watch;
  Adapter<User> get users => watch(usersAdapterProvider)..internalWatch = watch;
}

extension AdapterRefX on Ref {

  Adapter<App> get apps => watch(appsAdapterProvider)..internalWatch = watch as Watcher;
  Adapter<FileMetadata> get fileMetadata => watch(fileMetadataAdapterProvider)..internalWatch = watch as Watcher;
  Adapter<Release> get releases => watch(releasesAdapterProvider)..internalWatch = watch as Watcher;
  Adapter<Settings> get settings => watch(settingsAdapterProvider)..internalWatch = watch as Watcher;
  Adapter<User> get users => watch(usersAdapterProvider)..internalWatch = watch as Watcher;
}