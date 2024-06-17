import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_phoenix/flutter_phoenix.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
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
            '${stack?.toString().substring(0, 80)}',
            style: TextStyle(
              fontSize: 12,
              color: Colors.grey,
            ),
            textAlign: TextAlign.center,
          ),
          Gap(24),
          Text(
            'First try restart. If it does not fix it, try clear.\nLastly, you can send us an error report.',
            style: TextStyle(
              fontSize: 15,
              color: Colors.grey,
            ),
            textAlign: TextAlign.center,
          ),
          Gap(10),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              ElevatedButton(
                onPressed: () {
                  Phoenix.rebirth(context);
                  context.go('/');
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: Color.fromARGB(255, 255, 99, 99),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                child: const Text(
                  'Restart',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              Gap(10),
              ElevatedButton(
                onPressed: () {
                  ref.read(localStorageProvider).destroy().then((_) {
                    print('destory done');
                    Phoenix.rebirth(context);
                    context.go('/');
                  });
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: Color.fromARGB(255, 255, 66, 66),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                child: const Text(
                  'Clear',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              Gap(10),
              ElevatedButton(
                onPressed: () {},
                style: ElevatedButton.styleFrom(
                  backgroundColor: Color.fromARGB(255, 255, 22, 22),
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                child: const Text(
                  'Report',
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
