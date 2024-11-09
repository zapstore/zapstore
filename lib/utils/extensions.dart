import 'dart:math';

import 'package:dart_emoji/dart_emoji.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:go_router/go_router.dart';
import 'package:toastification/toastification.dart';
import 'package:zapstore/models/app.dart';

extension ContextX on BuildContext {
  ThemeData get theme => Theme.of(this);
  void showInfo(String message, {String? description, Icon? icon}) {
    toastification.show(
      context: this,
      type: ToastificationType.info,
      icon: icon ?? Icon(Icons.info),
      style: ToastificationStyle.fillColored,
      alignment: Alignment.topCenter,
      title: Text(
        message,
        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
      ),
      description: description != null ? Text(description) : null,
      autoCloseDuration: const Duration(seconds: 4),
      showProgressBar: false,
      closeOnClick: true,
    );
  }

  void showError(
      {required String title,
      String? description,
      Icon? icon,
      List<(String, Future<void> Function())> actions = const []}) {
    toastification.show(
      context: this,
      type: ToastificationType.error,
      style: ToastificationStyle.fillColored,
      alignment: Alignment.topCenter,
      icon: icon ?? Icon(Icons.error),
      title: Text(
        title,
        style: TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
        overflow: TextOverflow.ellipsis,
      ),
      showProgressBar: false,
      closeOnClick: true,
      description: description != null
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                RichText(text: TextSpan(text: description)),
                for (final (text, fn) in actions)
                  ElevatedButton(
                    style: ElevatedButton.styleFrom(
                        foregroundColor: Colors.white,
                        backgroundColor: Colors.transparent),
                    onPressed: () async {
                      await fn.call();
                      toastification.dismissAll(delayForAnimation: false);
                    },
                    child: Text(text),
                  ),
              ],
            )
          : null,
    );
  }

  void showZapstoreUpdate(App app) {
    toastification.show(
      context: this,
      type: ToastificationType.info,
      icon: Icon(Icons.info),
      style: ToastificationStyle.fillColored,
      title: Text(
        'Zapstore has a new version available',
        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
      ),
      description: Text('Tap to go to update screen'),
      callbacks: ToastificationCallbacks(
        onTap: (item) {
          toastification.dismissById(item.id);
          go('/updates/details', extra: app);
        },
      ),
      showProgressBar: false,
      closeButtonShowType: CloseButtonShowType.always,
      alignment: Alignment.topCenter,
    );
  }
}

BelongsTo<T> belongsTo<T extends DataModelMixin<T>>(Map<String, dynamic> map) {
  return BelongsTo<T>.fromJson(map);
}

HasMany<T> hasMany<T extends DataModelMixin<T>>(Map<String, dynamic> map) {
  return HasMany<T>.fromJson(map);
}

final emojiParser = EmojiParser();

extension StringWidget on String {
  String parseEmojis() {
    return replaceAllMapped(RegExp(':([a-z]*):'), (m) {
      return emojiParser.hasName(m[1]!) ? emojiParser.get(m[1]!).code : m[0]!;
    });
  }

  String substringMax(int size) {
    return substring(0, min(length, 80));
  }
}

const kI = "e593c54f840b32054dcad0fac15d57e4ac6523e31fe26b3087de6b07a2e9af58";
