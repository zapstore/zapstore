import 'dart:math';

import 'package:dart_emoji/dart_emoji.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:toastification/toastification.dart';

extension ContextX on BuildContext {
  ThemeData get theme => Theme.of(this);
  void showInfo(String message, {Icon? icon}) {
    toastification.show(
      context: this,
      type: ToastificationType.info,
      icon: icon ?? Icon(Icons.info),
      style: ToastificationStyle.fillColored,
      title: Text(
        message,
        style: TextStyle(fontSize: 16),
      ),
      autoCloseDuration: const Duration(seconds: 3),
      showProgressBar: false,
      closeOnClick: true,
      alignment: Alignment.bottomCenter,
    );
  }

  void showError({required String title, String? description, Icon? icon}) {
    toastification.show(
      context: this,
      type: ToastificationType.error,
      style: ToastificationStyle.fillColored,
      icon: icon ?? Icon(Icons.error),
      title: Text(
        title,
        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
      ),
      showProgressBar: false,
      closeOnClick: true,
      description: description != null ? Text(description) : null,
      alignment: Alignment.lerp(Alignment.bottomCenter, Alignment.center, 0.25),
    );
  }
}

BelongsTo<T> belongsTo<T extends DataModelMixin<T>>(Map<String, dynamic> map) {
  return BelongsTo<T>.fromJson(map);
}

HasMany<T> hasMany<T extends DataModelMixin<T>>(Map<String, dynamic> map) {
  return HasMany<T>.fromJson(map);
}

extension StringWidget on String {
  static final _emojiParser = EmojiParser();
  String parseEmojis() {
    return replaceAllMapped(RegExp(':([a-z]*):'), (m) {
      return _emojiParser.hasName(m[1]!) ? _emojiParser.get(m[1]!).code : m[0]!;
    });
  }

  String substringMax(int size) {
    return substring(0, min(length, 80));
  }
}

const kI = "e593c54f840b32054dcad0fac15d57e4ac6523e31fe26b3087de6b07a2e9af58";
