import 'package:flutter/material.dart';
import 'package:toastification/toastification.dart';
import 'package:zapstore/theme.dart';

extension ContextX on BuildContext {
  ThemeData get theme => Theme.of(this);
  void showInfo(String message, {String? description, Icon? icon}) {
    const actionColor = AppColors.darkActionPrimary;

    toastification.show(
      context: this,
      type: ToastificationType.info,
      icon: icon ?? const Icon(Icons.info, color: Colors.white),
      style: ToastificationStyle.fillColored,
      alignment: Alignment.topCenter,
      title: _ToastTitleWidget(message),
      description: description != null
          ? _ToastDescriptionWidget(description: description)
          : null,
      autoCloseDuration: const Duration(seconds: 4),
      showProgressBar: false,
      closeOnClick: true,
      primaryColor: actionColor,
      backgroundColor: actionColor,
      foregroundColor: Colors.white,
    );
  }

  void showError(
    String title, {
    String? description,
    Icon? icon,
    List<(String, Future<void> Function())> actions = const [],
  }) {
    toastification.show(
      context: this,
      type: ToastificationType.error,
      style: ToastificationStyle.fillColored,
      alignment: Alignment.topCenter,
      icon: icon ?? const Icon(Icons.error, color: Colors.white),
      title: _ToastTitleWidget(title),
      autoCloseDuration: const Duration(seconds: 5),
      showProgressBar: false,
      closeOnClick: true,
      foregroundColor: Colors.white,
      description: description != null
          ? _ToastDescriptionWidget(description: description, actions: actions)
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
      style: const TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.bold,
        color: Colors.white,
      ),
      overflow: TextOverflow.ellipsis,
      maxLines: 4,
    );
  }
}

class _ToastDescriptionWidget extends StatelessWidget {
  final String? description;
  final List<(String, Future<void> Function())> actions;

  const _ToastDescriptionWidget({this.description, this.actions = const []});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        RichText(
          text: TextSpan(
            text: description,
            style: const TextStyle(fontSize: 16, color: Colors.white),
          ),
        ),
        for (final (text, fn) in actions)
          ElevatedButton(
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
