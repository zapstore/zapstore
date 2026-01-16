import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/services/crash_report_cache_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';
import 'package:zapstore/widgets/crash_report_consent_dialog.dart';

/// Tracks whether the crash report prompt has been shown this session.
final _crashPromptHandledProvider = StateProvider<bool>((ref) => false);

/// Widget that listens for pending crash reports after app initialization
/// and shows a consent dialog if any are found.
class CrashReportPromptListener extends ConsumerWidget {
  const CrashReportPromptListener({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.listen<AsyncValue<void>>(appInitializationProvider, (previous, next) {
      // Only proceed once initialization completes
      if (next is! AsyncData) return;

      // Only show once per session
      final hasHandled = ref.read(_crashPromptHandledProvider);
      if (hasHandled) return;

      // Check for pending crashes
      _checkForCrashes(context, ref);
    });

    return const SizedBox.shrink();
  }

  Future<void> _checkForCrashes(BuildContext context, WidgetRef ref) async {
    try {
      final cacheService = ref.read(crashReportCacheServiceProvider);
      final crashes = await cacheService.getPendingCrashes();

      if (crashes.isEmpty) return;

      // Mark as handled to prevent showing again this session
      ref.read(_crashPromptHandledProvider.notifier).state = true;

      // Show dialog after frame completes
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (!context.mounted) return;

        final result = await showBaseDialog<CrashReportConsentResult>(
          context: context,
          dialog: CrashReportConsentDialog(crashes: crashes),
        );

        // Handle result
        switch (result) {
          case CrashReportConsentResult.sent:
            // Clear all crashes after sending
            await cacheService.clearAllCrashes();
            ref.invalidate(pendingCrashesProvider);
            if (context.mounted) {
              context.showInfo(
                'Crash report sent',
                description: 'Thank you for helping improve Zapstore.',
              );
            }
          case CrashReportConsentResult.discarded:
            // Clear all crashes
            await cacheService.clearAllCrashes();
            ref.invalidate(pendingCrashesProvider);
          case CrashReportConsentResult.kept:
          case null:
            // Do nothing - keep crashes for next time
            break;
        }
      });
    } catch (_) {
      // Silently fail - don't disrupt user experience
    }
  }
}
