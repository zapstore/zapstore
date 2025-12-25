import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/utils/extensions.dart';

final _zapstoreUpdatePromptHandledProvider = StateProvider<bool>(
  (ref) => false,
);

class ZapstoreUpdatePromptListener extends ConsumerWidget {
  const ZapstoreUpdatePromptListener({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.listen<CategorizedApps>(categorizedAppsProvider, (previous, next) {
      final hasHandled = ref.read(_zapstoreUpdatePromptHandledProvider);
      if (hasHandled) return;

      if (next.isLoading) return;

      final allUpdates = [...next.automaticUpdates, ...next.manualUpdates];
      final zapstoreUpdate =
          allUpdates.firstWhereOrNull((app) => app.isZapstoreApp);

      if (zapstoreUpdate == null) return;

      ref.read(_zapstoreUpdatePromptHandledProvider.notifier).state = true;

      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!context.mounted) return;
        context.showInfo(
          'Update Zapstore',
          description: 'A new Zapstore update is available.',
          actions: [
            (
              'Update',
              () async {
                if (!context.mounted) return;
                context.push('/updates/app/${zapstoreUpdate.identifier}');
              },
            ),
          ],
        );
      });
    });

    return const SizedBox.shrink();
  }
}

