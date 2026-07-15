import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/widgets/app_stack_container.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';
import 'package:zapstore/widgets/install_button.dart';

/// Lets a restored device install apps from an Amber-era installed-app backup.
class LegacyInstalledAppsDialog extends ConsumerWidget {
  const LegacyInstalledAppsDialog({super.key, required this.appIds});

  final List<String> appIds;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final (:authors, :identifiers) = decomposeAddressableIds(appIds);
    final appsState = ref.watch(
      query<App>(
        authors: authors,
        tags: {'#d': identifiers},
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-legacy-installed-recovery',
      ),
    );
    final apps = appsState.models.toList();

    return BaseDialog(
      title: const BaseDialogTitle('Restore apps'),
      titleIcon: const Icon(Icons.restore, size: 20),
      content: BaseDialogContent(
        children: [
          Text(
            'Found ${appIds.length} apps from your previous device. '
            'Choose which ones to install.',
          ),
          const SizedBox(height: 12),
          if (appsState is StorageLoading && apps.isEmpty)
            const Center(child: CircularProgressIndicator())
          else if (apps.isEmpty)
            const Text(
              'App details are unavailable right now. Try again later.',
            )
          else
            ...apps.map(
              (app) => ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(app.name ?? app.identifier),
                trailing: InstallButton(app: app, compact: true),
              ),
            ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Done'),
        ),
      ],
    );
  }
}
