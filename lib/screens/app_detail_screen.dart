import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/release.dart';

class AppDetailScreen extends HookConsumerWidget {
  final Release release;
  const AppDetailScreen({
    required this.release,
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Column(
      children: [
        for (final artifact in release.artifacts.toList())
          Expanded(
            child: Card(
              child: Text(
                  '${artifact.kind.toString()} / ${artifact.release.value?.content}'),
            ),
          ),
      ],
    );
  }
}
