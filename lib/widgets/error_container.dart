import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_phoenix/flutter_phoenix.dart';
import 'package:gap/gap.dart';
import 'package:http/http.dart' as http;
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/feedback.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/utils/theme.dart';

class ErrorContainer extends HookConsumerWidget {
  final Object exception;
  final StackTrace? stack;
  const ErrorContainer({super.key, required this.exception, this.stack});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Container(
      color: kBackgroundColor,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(
            Icons.error_outline,
            color: Colors.red,
            size: 60,
          ),
          const SizedBox(height: 16),
          const Text(
            'Dang, something went wrong!',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.bold,
            ),
          ),
          Gap(10),
          Text(
            exception.toString(),
            style: TextStyle(
              fontSize: 16,
              color: Colors.grey,
            ),
            textAlign: TextAlign.center,
          ),
          Gap(6),
          Text(
            stack?.toString().substringMax(80) ?? '',
            style: TextStyle(
              fontSize: 12,
              color: Colors.grey,
            ),
            textAlign: TextAlign.center,
          ),
          Gap(12),
          Text(
            'Reporting an error will send device information',
            style: TextStyle(
              fontSize: 14,
              color: Colors.grey,
            ),
            textAlign: TextAlign.center,
          ),
          Gap(24),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              ElevatedButton(
                onPressed: () {
                  ref.read(localStorageProvider).destroy().then((_) {
                    if (context.mounted) {
                      Phoenix.rebirth(context);
                      context.go('/');
                    }
                  });
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: Color.fromARGB(255, 251, 89, 89),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                child: const Text(
                  'Clear and reload app',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              Gap(10),
              ElevatedButton(
                onPressed: () => _sendErrorReport(ref, exception, stack),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Color.fromARGB(255, 255, 22, 22),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                child: const Text(
                  'Report error',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

Widget materialErrorBuilder(BuildContext context, Widget? widget) {
  if (widget is Scaffold || widget is Navigator) {
    ErrorWidget.builder = (details) => Scaffold(
          body: ErrorContainer(
            exception: details.exception,
            stack: details.stack,
          ),
        );
    return widget!;
  }
  ErrorWidget.builder = (details) => ErrorContainer(
        exception: details.exception,
        stack: details.stack,
      );
  if (widget != null) return widget;
  throw StateError('widget is null');
}

Future<void> _sendErrorReport(
    WidgetRef ref, Object exception, StackTrace? stack) async {
  final systemInfo = await ref.read(systemInfoProvider.future);

  var map = {
    'e': exception.toString(),
    if (stack != null) 'stack': stack.toString(),
    'info': systemInfo.androidInfo.toString()
  };

  final client = http.Client();
  final event = AppFeedback(content: jsonEncode(map)).sign(kI);
  await client.post(Uri.parse('https://relay.zap.store/'),
      body: jsonEncode(event.toMap()),
      headers: {'Content-Type': 'application/json'});
  client.close();
}
