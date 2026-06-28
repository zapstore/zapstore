import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/notification_service.dart';

const kMinimumReportDescriptionLength = 20;
final _hexIdentifier = RegExp(r'^[0-9a-f]{64}$', caseSensitive: false);

bool canSubmitAppReport({
  required ReportType? violationType,
  required String description,
  required bool isPublishing,
}) =>
    !isPublishing &&
    violationType != null &&
    description.trim().length >= kMinimumReportDescriptionLength;

bool canReportApp(App app) =>
    reportableAppEventId(app) != null && _hexIdentifier.hasMatch(app.pubkey);

String? reportableAppEventId(App app) {
  if (_hexIdentifier.hasMatch(app.event.id)) return app.event.id;

  try {
    final partial = app.toPartial<PartialApp>();
    partial.event.pubkey = app.pubkey;
    final recomputedId = partial.event.id;
    if (recomputedId != null && _hexIdentifier.hasMatch(recomputedId)) {
      return recomputedId;
    }
  } catch (_) {
    return null;
  }

  return null;
}

/// Opens the NIP-56 report flow for an app listing.
void showAppReportSheet(BuildContext context, App app) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    builder: (_) => AppReportSheet(app: app),
  );
}

class AppReportSheet extends HookConsumerWidget {
  const AppReportSheet({super.key, required this.app});

  final App app;

  static const _reportTypes = <ReportType>[
    ReportType.malware,
    ReportType.impersonation,
    ReportType.spam,
    ReportType.illegal,
    ReportType.other,
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selectedType = useState<ReportType?>(null);
    final descriptionController = useTextEditingController();
    final isPublishing = useState(false);
    final submissionError = useState<String?>(null);
    useListenable(descriptionController);

    final description = descriptionController.text.trim();
    final canSubmit = canSubmitAppReport(
      violationType: selectedType.value,
      description: description,
      isPublishing: isPublishing.value,
    );

    return PopScope(
      canPop: !isPublishing.value,
      child: Padding(
        padding: EdgeInsets.only(
          left: 16,
          right: 16,
          top: 16,
          bottom: MediaQuery.of(context).viewInsets.bottom + 16,
        ),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      'Report ${app.name ?? 'app'}',
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                  ),
                  IconButton(
                    tooltip: 'Close',
                    onPressed: isPublishing.value
                        ? null
                        : () => Navigator.pop(context),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                'Report only violations of Zapstore’s reporting policy, such '
                'as malicious software or a deceptive listing. This is not '
                'for reviews or disagreements.',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 16),
              DropdownButtonFormField<ReportType>(
                value: selectedType.value,
                decoration: const InputDecoration(
                  labelText: 'Policy violation',
                  border: OutlineInputBorder(),
                ),
                hint: const Text('Select a violation'),
                items: [
                  for (final type in _reportTypes)
                    DropdownMenuItem(value: type, child: Text(_labelFor(type))),
                ],
                onChanged: isPublishing.value
                    ? null
                    : (type) {
                        selectedType.value = type;
                        submissionError.value = null;
                      },
              ),
              const SizedBox(height: 12),
              TextField(
                controller: descriptionController,
                enabled: !isPublishing.value,
                minLines: 4,
                maxLines: 7,
                maxLength: 1000,
                textCapitalization: TextCapitalization.sentences,
                decoration: InputDecoration(
                  labelText: 'Describe the violation',
                  hintText: 'Explain what makes this listing violate policy.',
                  border: const OutlineInputBorder(),
                  errorText:
                      description.isNotEmpty &&
                          description.length < kMinimumReportDescriptionLength
                      ? 'Use at least $kMinimumReportDescriptionLength characters.'
                      : null,
                ),
                onChanged: (_) => submissionError.value = null,
              ),
              if (submissionError.value case final error?) ...[
                const SizedBox(height: 8),
                Text(
                  error,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              const SizedBox(height: 8),
              Text(
                'Your report will be public and signed by your Nostr identity.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 16),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: canSubmit
                      ? () => _publish(
                          context: context,
                          ref: ref,
                          app: app,
                          violationType: selectedType.value!,
                          description: description,
                          isPublishing: isPublishing,
                          submissionError: submissionError,
                        )
                      : null,
                  child: isPublishing.value
                      ? const SizedBox(
                          height: 20,
                          width: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Publish report'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static String _labelFor(ReportType type) => switch (type) {
    ReportType.malware => 'Malicious software',
    ReportType.impersonation => 'Impersonation',
    ReportType.spam => 'Spam or deceptive listing',
    ReportType.illegal => 'Potentially illegal content',
    ReportType.other => 'Other policy violation',
    _ => type.displayName,
  };

  static Future<void> _publish({
    required BuildContext context,
    required WidgetRef ref,
    required App app,
    required ReportType violationType,
    required String description,
    required ValueNotifier<bool> isPublishing,
    required ValueNotifier<String?> submissionError,
  }) async {
    final appEventId = reportableAppEventId(app);
    if (appEventId == null) {
      submissionError.value = 'This app listing cannot be reported.';
      return;
    }

    final signer = ref.read(Signer.activeSignerProvider);
    if (signer == null) {
      submissionError.value = 'Sign in with Amber to publish a report.';
      return;
    }

    isPublishing.value = true;
    submissionError.value = null;
    try {
      final report = await PartialReport.forContent(
        contentId: appEventId,
        authorPubkey: app.pubkey,
        violationType: violationType,
        reason: description,
      ).signWith(signer);

      await report.save();
      await report.publish(relays: 'AppCatalog');

      if (context.mounted) {
        Navigator.pop(context);
        context.showInfo('Report published');
      }
    } catch (_) {
      submissionError.value =
          'Could not publish the report. Check your connection and retry.';
    } finally {
      isPublishing.value = false;
    }
  }
}
