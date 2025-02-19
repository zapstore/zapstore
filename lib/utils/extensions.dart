import 'dart:async';
import 'dart:math';

import 'package:dart_emoji/dart_emoji.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:intl/intl.dart';
import 'package:toastification/toastification.dart';

extension ContextX on BuildContext {
  ThemeData get theme => Theme.of(this);
  void showInfo(String message, {String? description, Icon? icon}) {
    toastification.show(
      context: this,
      type: ToastificationType.info,
      icon: icon ?? Icon(Icons.info),
      style: ToastificationStyle.fillColored,
      alignment: Alignment.topCenter,
      title: _ToastTitleWidget(message),
      description: description != null
          ? _ToastDescriptionWidget(description: description)
          : null,
      autoCloseDuration: const Duration(seconds: 4),
      showProgressBar: false,
      closeOnClick: true,
    );
  }

  void showError(String title,
      {String? description,
      Icon? icon,
      List<(String, Future<void> Function())> actions = const []}) {
    toastification.show(
      context: this,
      type: ToastificationType.error,
      style: ToastificationStyle.fillColored,
      alignment: Alignment.topCenter,
      icon: icon ?? Icon(Icons.error),
      title: _ToastTitleWidget(title),
      showProgressBar: false,
      closeOnClick: true,
      description: description != null
          ? _ToastDescriptionWidget(
              description: description,
              actions: actions,
            )
          : null,
    );
  }
}

class _ToastTitleWidget extends StatelessWidget {
  final String text;
  const _ToastTitleWidget(this.text);

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
      overflow: TextOverflow.ellipsis,
      maxLines: 4,
    );
  }
}

class _ToastDescriptionWidget extends StatelessWidget {
  final String? description;
  final List<(String, Future<void> Function())> actions;

  _ToastDescriptionWidget({this.description, this.actions = const []});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        RichText(
          text: TextSpan(
            text: description,
            style: TextStyle(fontSize: 16),
          ),
        ),
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
    );
  }
}

BelongsTo<T> belongsTo<T extends DataModelMixin<T>>(Map<String, dynamic>? map) {
  return map != null ? BelongsTo<T>.fromJson(map) : BelongsTo<T>();
}

HasMany<T> hasMany<T extends DataModelMixin<T>>(Map<String, dynamic>? map) {
  return map != null ? HasMany<T>.fromJson(map) : HasMany<T>();
}

final emojiParser = EmojiParser();

extension StringWidget on String {
  String parseEmojis() {
    return replaceAllMapped(RegExp(':([a-z]*):'), (m) {
      return emojiParser.hasName(m[1]!) ? emojiParser.get(m[1]!).code : m[0]!;
    });
  }

  String safeSubstring(int size) {
    return substring(0, min(length, size)) + (size < length ? '...' : '');
  }

  String get shorten {
    // npub1wf4puf...43dgh9
    if (length < 18) return this;
    final leading = substring(0, 9);
    final trailing = substring(length - 6, length - 1);
    return '$leading...$trailing';
  }

  String removeParenthesis() {
    return replaceAll(RegExp(r'\([^()]*\)'), '');
  }
}

extension TextExt on Text {
  Widget get bold {
    return Text(data!, style: TextStyle(fontWeight: FontWeight.bold));
  }
}

const kZapstoreAppIdentifier = 'dev.zapstore.app';

final kNumberFormatter = NumberFormat('#,###');

// stream utils

Stream<List<T>> bufferByTime<T>(Stream<T> source, Duration duration) {
  final controller = StreamController<List<T>>();

  final buffer = [];

  Timer? timer;

  // Listen to the source stream
  source.listen((data) {
    buffer.add(data);

    // If there's no active timer, start one
    timer ??= Timer(duration, () {
      controller.add(List.from(buffer)); // Emit buffered data
      buffer.clear(); // Clear the buffer
      timer = null; // Reset the timer
    });
  }, onDone: () {
    // Emit remaining items in the buffer when the source is done
    if (buffer.isNotEmpty) {
      controller.add(List.from(buffer));
    }
    controller.close(); // Close the controller
  });

  return controller.stream; // Return the buffered stream
}
