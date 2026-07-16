import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';

enum DeviceRestoreAction { pasteKey, amber }

class DeviceRestoreResult {
  const DeviceRestoreResult(this.action, {this.key});

  final DeviceRestoreAction action;
  final String? key;
}

/// Lets a user replace the current device identity with a recovered one.
class DeviceRestoreDialog extends HookConsumerWidget {
  const DeviceRestoreDialog({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = useTextEditingController();
    final amberInstalled = ref.watch(
      packageManagerProvider.select(
        (state) => state.installed.containsKey(kAmberPackageId),
      ),
    );

    return BaseDialog(
      title: const BaseDialogTitle('Restore device key'),
      titleIcon: const Icon(Icons.restore, size: 20),
      content: BaseDialogContent(
        children: [
          const Text(
            'Restore your settings and saved apps with a backed up device key.',
          ),
          const SizedBox(height: 10),
          const Text(
            'Alternatively, you can sign in with Amber and settings may be recovered that way.',
          ),
          const SizedBox(height: 16),
          TextField(
            controller: controller,
            autocorrect: false,
            enableSuggestions: false,
            decoration: const InputDecoration(
              labelText: 'Device key',
              hintText: 'nsec1…',
            ),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: () {
              final value = controller.text.trim();
              if (value.isEmpty) return;
              Navigator.pop(
                context,
                DeviceRestoreResult(DeviceRestoreAction.pasteKey, key: value),
              );
            },
            icon: const Icon(Icons.key),
            label: const Text('Restore'),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
      ],
    );
  }
}
