import 'dart:io';

import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

const _channel = MethodChannel('dev.zapstore/app_restart');
const _markerFileName = '.clear_on_restart';

/// Checks if storage should be cleared on this launch, and clears the marker.
/// Call this BEFORE initializing storage.
Future<void> maybeClearStorage(String dbPath) async {
  final dir = await getApplicationSupportDirectory();
  final marker = File('${dir.path}/$_markerFileName');
  if (await marker.exists()) {
    final dbFile = File(dbPath);
    if (await dbFile.exists()) {
      await dbFile.delete();
    }
    await marker.delete();
  }
}

/// Sets a marker file and triggers a native app restart.
Future<void> restartApp() async {
  final dir = await getApplicationSupportDirectory();
  final marker = File('${dir.path}/$_markerFileName');
  await marker.create();
  await _channel.invokeMethod('restart');
}
