import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/crash_report_cache_service.dart';
import 'package:zapstore/services/error_reporting_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';

/// Result of the crash report consent dialog.
enum CrashReportConsentResult { sent, kept, discarded }

/// Dialog that prompts user to send, keep, or discard cached crash reports.
class CrashReportConsentDialog extends HookConsumerWidget {
  const CrashReportConsentDialog({super.key, required this.crashes});

  final List<CrashReport> crashes;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isLoading = useState(false);
    final commentController = useTextEditingController();
    final theme = Theme.of(context);
    final crashCount = crashes.length;
    final firstCrash = crashes.first;

    return BaseDialog(
      titleIcon: Icon(
        Icons.bug_report_outlined,
        color: theme.colorScheme.error,
      ),
      titleIconColor: theme.colorScheme.error,
      title: BaseDialogTitle(
        crashCount == 1 ? 'Crash Report' : '$crashCount Crash Reports',
      ),
      content: BaseDialogContent(
        children: [
          Text(
            'Zapstore encountered ${crashCount == 1 ? 'an error' : '$crashCount errors'} '
            'during your last session. Would you like to send '
            '${crashCount == 1 ? 'a report' : 'reports'} to help improve the app?',
            style: theme.textTheme.bodyMedium,
          ),
          const Gap(16),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: theme.colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (firstCrash.appVersion != null) ...[
                  Text(
                    'Version: ${firstCrash.appVersion}',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const Gap(4),
                ],
                Text(
                  firstCrash.exceptionType,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                    fontFamily: 'monospace',
                  ),
                ),
                const Gap(4),
                Text(
                  firstCrash.message.length > 200
                      ? '${firstCrash.message.substring(0, 200)}...'
                      : firstCrash.message,
                  style: theme.textTheme.bodySmall?.copyWith(
                    fontFamily: 'monospace',
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          const Gap(16),
          TextField(
            controller: commentController,
            decoration: InputDecoration(
              hintText: 'What were you doing when this happened? (optional)',
              hintStyle: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
              ),
              contentPadding: const EdgeInsets.all(12),
            ),
            maxLines: 3,
            minLines: 1,
            textCapitalization: TextCapitalization.sentences,
          ),
          const Gap(16),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                Icons.lock_outline,
                size: 16,
                color: theme.colorScheme.onSurfaceVariant,
              ),
              const Gap(8),
              Expanded(
                child: Text(
                  'Reports are encrypted and automatically deleted after 30 days. '
                  'No personal data is collected.',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: isLoading.value
              ? null
              : () {
                  Navigator.of(context).pop(CrashReportConsentResult.discarded);
                },
          child: const Text('Discard'),
        ),
        TextButton(
          onPressed: isLoading.value
              ? null
              : () {
                  Navigator.of(context).pop(CrashReportConsentResult.kept);
                },
          child: const Text('Keep for Later'),
        ),
        FilledButton(
          onPressed: isLoading.value
              ? null
              : () async {
                  isLoading.value = true;
                  try {
                    final comment = commentController.text.trim();
                    await ref
                        .read(errorReportingServiceProvider)
                        .sendCachedCrashReports(
                          crashes,
                          userComment: comment.isNotEmpty ? comment : null,
                        );
                    if (context.mounted) {
                      Navigator.of(context).pop(CrashReportConsentResult.sent);
                    }
                  } catch (e) {
                    if (context.mounted) {
                      context.showError(
                        'Failed to send report',
                        description: 'Please try again later.',
                      );
                    }
                    isLoading.value = false;
                  }
                },
          style: FilledButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
          child: isLoading.value
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : Text(crashCount == 1 ? 'Send Report' : 'Send Reports'),
        ),
      ],
    );
  }
}
