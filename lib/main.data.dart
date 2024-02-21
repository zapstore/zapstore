

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: directives_ordering, top_level_function_literal_block, depend_on_referenced_packages

import 'package:flutter_data/flutter_data.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:path_provider/path_provider.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:zapstore/models/file_metadata.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/models/user.dart';

// ignore: prefer_function_declarations_over_variables
ConfigureRepositoryLocalStorage configureRepositoryLocalStorage = ({FutureFn<String>? baseDirFn, List<int>? encryptionKey, LocalStorageClearStrategy? clear}) {
  if (!kIsWeb) {
    baseDirFn ??= () => getApplicationDocumentsDirectory().then((dir) => dir.path);
  } else {
    baseDirFn ??= () => '';
  }
  
  return hiveLocalStorageProvider.overrideWith(
    (ref) => HiveLocalStorage(
      hive: ref.read(hiveProvider),
      baseDirFn: baseDirFn,
      encryptionKey: encryptionKey,
      clear: clear,
    ),
  );
};

final repositoryProviders = <String, Provider<Repository<DataModelMixin>>>{
  'fileMetadata': fileMetadataRepositoryProvider,
'releases': releasesRepositoryProvider,
'users': usersRepositoryProvider
};

final repositoryInitializerProvider =
  FutureProvider<RepositoryInitializer>((ref) async {
    DataHelpers.setInternalType<FileMetadata>('fileMetadata');
    DataHelpers.setInternalType<Release>('releases');
    DataHelpers.setInternalType<User>('users');
    final adapters = <String, RemoteAdapter>{'fileMetadata': ref.watch(internalFileMetadataRemoteAdapterProvider), 'releases': ref.watch(internalReleasesRemoteAdapterProvider), 'users': ref.watch(internalUsersRemoteAdapterProvider)};
    final remotes = <String, bool>{'fileMetadata': true, 'releases': true, 'users': true};

    await ref.watch(graphNotifierProvider).initialize();

    // initialize and register
    for (final type in repositoryProviders.keys) {
      final repository = ref.read(repositoryProviders[type]!);
      repository.dispose();
      await repository.initialize(
        remote: remotes[type],
        adapters: adapters,
      );
      internalRepositories[type] = repository;
    }

    return RepositoryInitializer();
});
extension RepositoryWidgetRefX on WidgetRef {
  Repository<FileMetadata> get fileMetadata => watch(fileMetadataRepositoryProvider)..remoteAdapter.internalWatch = watch;
  Repository<Release> get releases => watch(releasesRepositoryProvider)..remoteAdapter.internalWatch = watch;
  Repository<User> get users => watch(usersRepositoryProvider)..remoteAdapter.internalWatch = watch;
}

extension RepositoryRefX on Ref {

  Repository<FileMetadata> get fileMetadata => watch(fileMetadataRepositoryProvider)..remoteAdapter.internalWatch = watch as Watcher;
  Repository<Release> get releases => watch(releasesRepositoryProvider)..remoteAdapter.internalWatch = watch as Watcher;
  Repository<User> get users => watch(usersRepositoryProvider)..remoteAdapter.internalWatch = watch as Watcher;
}