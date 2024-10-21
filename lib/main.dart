import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:app_links/app_links.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_phoenix/flutter_phoenix.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/navigation/router.dart';
import 'package:zapstore/utils/debounce.dart';
import 'package:zapstore/utils/theme.dart';
import 'package:zapstore/widgets/error_container.dart';

const kDbVersion = 2;
final appLinks = AppLinks();

/// Application entry point.
///  - Initializes Riverpod (and Flutter Data local storage)
///  - Handles application errors
///  - Triggers routing
void main() {
  runZonedGuarded(() {
    runApp(
      Phoenix(
        child: ProviderScope(
          overrides: [
            localStorageProvider.overrideWithValue(
              LocalStorage(
                baseDirFn: () async {
                  final path = (await getApplicationSupportDirectory()).path;
                  print('Initializing local storage at $path');
                  return path;
                },
                clear: LocalStorageClearStrategy.whenError,
              ),
            )
          ],
          child: const ZapstoreApp(),
        ),
      ),
    );
  }, errorHandler);

  FlutterError.onError = (_) => errorHandler(_.exception, _.stack);
}

class ZapstoreApp extends ConsumerWidget {
  const ZapstoreApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.watch(relayListenerProvider);

    return MaterialApp.router(
      builder: materialErrorBuilder,
      routerConfig: appRouter,
      debugShowCheckedModeBanner: false,
      theme: theme,
    );
  }
}

Directory? _dir;

void errorHandler(Object exception, StackTrace? stack) {
  debounce((exception: exception, stack: stack), (records) async {
    if (records.isEmpty) return;
    _dir ??= await getApplicationDocumentsDirectory();
    final file = File('${_dir!.path}/errors.json');

    late final Map<String, dynamic> errorMap;
    if (await file.exists()) {
      final contents = await file.readAsString();
      errorMap =
          contents.isNotEmpty ? jsonDecode(await file.readAsString()) : {};
    } else {
      errorMap = {};
    }

    for (final record in records) {
      final full =
          '${record.exception}${record.stack?.toString() ?? ''}${DateTime.now().toIso8601String()}';
      final key = full.split('\n').take(2).join();
      // Only keep longest stack of similar errors, prevents duplicates
      if (full.length > (errorMap[key]?.length ?? 0)) {
        errorMap[key] = full;
      }
    }
    await file.writeAsString(jsonEncode(errorMap));
  });
  print(exception);
  print(stack);
}
