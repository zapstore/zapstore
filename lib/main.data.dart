// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: directives_ordering, top_level_function_literal_block, depend_on_referenced_packages

import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/app_curation_set.dart';
import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';

final adapterProvidersMap = <String, Provider<Adapter<DataModelMixin>>>{
  'appCurationSets': appCurationSetsAdapterProvider,
  'apps': appsAdapterProvider,
  'fileMetadata': fileMetadataAdapterProvider,
  'localApps': localAppsAdapterProvider,
  'releases': releasesAdapterProvider,
  'settings': settingsAdapterProvider,
  'users': usersAdapterProvider
};

extension AdapterWidgetRefX on WidgetRef {
  Adapter<AppCurationSet> get appCurationSets =>
      watch(appCurationSetsAdapterProvider)..internalWatch = watch;
  Adapter<App> get apps => watch(appsAdapterProvider)..internalWatch = watch;
  Adapter<FileMetadata> get fileMetadata =>
      watch(fileMetadataAdapterProvider)..internalWatch = watch;
  Adapter<LocalApp> get localApps =>
      watch(localAppsAdapterProvider)..internalWatch = watch;
  Adapter<Release> get releases =>
      watch(releasesAdapterProvider)..internalWatch = watch;
  Adapter<Settings> get settings =>
      watch(settingsAdapterProvider)..internalWatch = watch;
  Adapter<User> get users => watch(usersAdapterProvider)..internalWatch = watch;
}

extension AdapterRefX on Ref {
  Adapter<AppCurationSet> get appCurationSets =>
      watch(appCurationSetsAdapterProvider)..internalWatch = watch;
  Adapter<App> get apps => watch(appsAdapterProvider)..internalWatch = watch;
  Adapter<FileMetadata> get fileMetadata =>
      watch(fileMetadataAdapterProvider)..internalWatch = watch;
  Adapter<LocalApp> get localApps =>
      watch(localAppsAdapterProvider)..internalWatch = watch;
  Adapter<Release> get releases =>
      watch(releasesAdapterProvider)..internalWatch = watch;
  Adapter<Settings> get settings =>
      watch(settingsAdapterProvider)..internalWatch = watch;
  Adapter<User> get users => watch(usersAdapterProvider)..internalWatch = watch;

}

