import 'dart:async';

import 'package:flutter/services.dart';

class SilentInstallPlugin {
  static const MethodChannel _channel = MethodChannel('silent_install_plugin');

  /// Install an APK file silently if possible, or with user confirmation if required.
  /// 
  /// Returns a map with:
  /// - 'isSuccess': boolean indicating if installation was successful
  /// - 'errorMessage': String containing error message if any
  static Future<Map<String, dynamic>> install(String filePath) async {
    try {
      final result = await _channel.invokeMethod('install', {'filePath': filePath});
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      return {
        'isSuccess': false,
        'errorMessage': e.message ?? 'Unknown error occurred'
      };
    }
  }

  /// Check if the app can install packages silently without user interaction
  static Future<bool> canInstallSilently() async {
    try {
      final result = await _channel.invokeMethod('canInstallSilently');
      return result;
    } on PlatformException catch (_) {
      return false;
    }
  }
}