import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';
import 'package:zapstore/widgets/download_text_container.dart';
import 'package:zapstore/widgets/relevant_who_follow_container.dart';
import 'package:zapstore/widgets/sign_in_button.dart';

class InstallAlertDialog extends HookConsumerWidget {
  const InstallAlertDialog({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query publisher profile (app.pubkey is always present)
    final publisherState = ref.watch(query<Profile>(
      authors: {app.pubkey},
      source: const LocalAndRemoteSource(relays: {'social', 'vertex'}, cachedFor: Duration(hours: 2)),
    ));
    final publisher = switch (publisherState) {
      StorageData(:final models) => models.firstOrNull,
      _ => null,
    };
    if (publisher == null) {
      return const SizedBox.shrink();
    }
    final trustedSignerNotifier = useState(false);
    final profile = ref.watch(Signer.activeProfileProvider(LocalSource()));
    final theme = Theme.of(context);
    final baseTextSize = theme.textTheme.bodyMedium?.fontSize ?? 14.0;

    return BaseDialog(
      title: const BaseDialogTitle('Trust this app?'),
      content: BaseDialogContent(
        children: [
          if (app.isRelaySigned) ...[
            AuthorContainer(
              profile: publisher,
              beforeText: 'The',
              afterText:
                  ' relay makes the ${app.name ?? app.identifier} app available but did not develop it. ',
              oneLine: false,
              size: baseTextSize,
            ),
            Gap(10),
            DownloadTextContainer(
              beforeText:
                  '${app.name ?? app.identifier} will be installed from its original release location:',
              oneLine: false,
              showFullUrl: true,
              url: app.latestFileMetadata!.urls.first,
              size: baseTextSize,
            ),
          ] else ...[
            AuthorContainer(
              profile: publisher,
              beforeText: '${app.name ?? app.identifier} is published by',
              afterText: '.',
              oneLine: false,
              size: baseTextSize,
            ),
            Gap(14),
            profile != null
                ? RelevantWhoFollowContainer(
                    toNpub: publisher.npub,
                    trailingText:
                        ' and others follow ${publisher.nameOrNpub} on Nostr.\n\nThis information helps you determine ${publisher.nameOrNpub}\'s reputation. They are not endorsements of the ${app.name} app.',
                    size: baseTextSize,
                  )
                : SignInButton(
                    label:
                        'Sign in to view the publisher\'s reputable followers',
                    minimal: true,
                    requireNip55: true,
                  ),
            Gap(14),
            Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Switch(
                  value: trustedSignerNotifier.value,
                  onChanged: (value) {
                    trustedSignerNotifier.value = value;
                  },
                ),
                Gap(4),
                Expanded(
                  child: Row(
                    children: [
                      Text(
                        'Always trust',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurface,
                        ),
                      ),
                      Gap(4),
                      AuthorContainer(
                        beforeText: '',
                        profile: publisher,
                        size: baseTextSize,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            // NOTE: can't use context.pop()
            Navigator.of(context).pop();
          },
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () {
            Navigator.of(
              context,
            ).pop((trustPermanently: trustedSignerNotifier.value));
          },
          style: FilledButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          ),
          child: const Text('Trust and install app'),
        ),
      ],
    );
  }
}
