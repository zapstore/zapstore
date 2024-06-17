import 'dart:async';
import 'dart:io';

import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_phoenix/flutter_phoenix.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:zapstore/navigation/router.dart';
import 'package:zapstore/utils/debounce.dart';
import 'package:zapstore/utils/theme.dart';
import 'package:zapstore/widgets/error_container.dart';

const kDbVersion = 1;

/// Application entry point.
///  - Initializes Riverpod (and Flutter Data local storage)
///  - Handles errors globally
///  - Calls router
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

class ZapstoreApp extends StatelessWidget {
  const ZapstoreApp({super.key});

  @override
  Widget build(BuildContext context) {
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
    // TODO Implement reporting
    final map = records.groupSetsBy((err) => '${err.exception}\n${err.stack}');
    print(map.length);

    //   final text =
    //     '${DateTime.now().toIso8601String()}\n${err.exception}\n${err.stack}';
    // // print('${err.exception}\n${err.stack}');

    // final hash = sha256.convert(utf8.encode('${err.exception}\n${err.stack}'));
    // print(hash);
    // print(text);
    // print('-----');

    _dir ??= await getApplicationDocumentsDirectory();
    // // final file = File('${directory.path}/data.txt');
    // // await file.writeAsString(newContents);

    // collect system information
  });
}
