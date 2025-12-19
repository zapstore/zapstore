import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:go_router/go_router.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/url_utils.dart';
import 'package:zapstore/services/download/download_service.dart';
import 'package:zapstore/widgets/zap_widgets.dart';

import 'author_container.dart';
import 'version_pill_widget.dart';
import 'install_button.dart';
import '../theme.dart';

class AppCard extends HookConsumerWidget {
  final App? app;
  final bool isLoading;
  final bool showUpdateArrow;
  final bool showSignedBy;
  final bool showUpdateButton;
  final bool showZapEncouragement;

  const AppCard({
    super.key,
    this.app,
    this.isLoading = false,
    this.showUpdateArrow = false,
    this.showSignedBy = true,
    this.showUpdateButton = false,
    this.showZapEncouragement = false,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Show skeleton when loading or app is null
    if (isLoading || app == null) {
      return _buildSkeleton(context);
    }

    final currentApp = app!;
    final needsPublisher = showSignedBy || showZapEncouragement;
    final descriptionStyle = context.textTheme.bodyMedium?.copyWith(
      height: 1.5,
      color: AppColors.darkOnSurfaceSecondary,
    );
    final descriptionText = app!.description.isNotEmpty
        ? _stripMarkdown(app!.description)
        : 'No description available';

    Widget buildCard(Profile? publisher) => GestureDetector(
      onTap: () {
        final segments = GoRouterState.of(context).uri.pathSegments;
        final first = segments.isNotEmpty ? segments.first : 'search';
        context.push('/$first/app/${app!.identifier}');
      },
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(20),
          color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.6),
          border: Border.all(
            color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
            width: 1,
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // App Icon (64px, radius 15 to match old design)
            _buildAppIcon(context),

            const SizedBox(width: 18),

            // App Details with professional typography
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // App Name and Version
                  if (showUpdateButton) ...[
                    // When showing update button: name on top, version below
                    Text(
                      app!.name ?? app!.identifier,
                      style: context.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w900,
                        letterSpacing: 0.1,
                      ),
                      overflow: TextOverflow.ellipsis,
                      maxLines: 1,
                    ),
                    const SizedBox(height: 6),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: VersionPillWidget(
                        app: app!,
                        showUpdateArrow: showUpdateArrow,
                      ),
                    ),
                  ] else ...[
                    // Default: name and version in same row
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Expanded(
                          child: Text(
                            app!.name ?? app!.identifier,
                            style: context.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.w900,
                              letterSpacing: 0.1,
                            ),
                            overflow: TextOverflow.ellipsis,
                            maxLines: 1,
                          ),
                        ),
                        const SizedBox(width: 12),
                        VersionPillWidget(
                          app: app!,
                          showUpdateArrow: showUpdateArrow,
                        ),
                      ],
                    ),
                  ],

                  const SizedBox(height: 12),

                  // App Description rendered as plain text (markdown stripped)
                  Text(
                    descriptionText,
                    style: descriptionStyle,
                    overflow: TextOverflow.ellipsis,
                    maxLines: 3,
                    softWrap: true,
                  ),

                  // Author signature (only if app has publisher)
                  if (showSignedBy && publisher != null) ...[
                    Gap(14),
                    Theme(
                      data: Theme.of(context).copyWith(
                        textTheme: context.textTheme.copyWith(
                          bodySmall: context.textTheme.bodySmall?.copyWith(
                            color: AppColors.darkOnSurfaceSecondary,
                          ),
                        ),
                      ),
                      child: AuthorContainer(
                        profile: publisher,
                        beforeText: 'Published by',
                        size: context.textTheme.bodyMedium!.fontSize!,
                        oneLine: true,
                        app: app,
                      ),
                    ),
                  ],

                  // Update button (for apps with updates or currently downloading/installing)
                  if (showUpdateButton) _AppCardUpdateButtonSection(app: app!),

                  // Zap encouragement (only for downloading/installing developer-signed apps)
                  if (showZapEncouragement)
                    _AppCardZapEncouragementSection(
                      app: app!,
                      publisher: publisher,
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );

    if (!needsPublisher) return buildCard(null);

    return Consumer(
      builder: (context, ref, _) {
        final publisherState = ref.watch(
          query<Profile>(
            authors: {currentApp.event.pubkey},
            source: const LocalAndRemoteSource(
              relays: {'social', 'vertex'},
              cachedFor: Duration(hours: 2),
            ),
          ),
        );

        final publisher = switch (publisherState) {
          StorageData(:final models) => models.firstOrNull,
          _ => null,
        };

        return buildCard(publisher);
      },
    );
  }

  Widget _buildAppIcon(BuildContext context) {
    final iconUrl = firstValidHttpUrl(app!.icons);

    return Container(
      width: 58,
      height: 58,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(14),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.1),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(14),
        child: iconUrl != null
            ? CachedNetworkImage(
                imageUrl: iconUrl,
                fit: BoxFit.cover,
                fadeInDuration: const Duration(milliseconds: 500),
                fadeOutDuration: const Duration(milliseconds: 200),
                placeholder: (_, url) => const SizedBox.shrink(),
                errorWidget: (context, url, error) => Center(
                  child: Icon(
                    Icons.broken_image_outlined,
                    size: 32,
                    color: Colors.grey[400],
                  ),
                ),
              )
            : Center(
                child: Icon(
                  Icons.apps_outlined,
                  size: 32,
                  color: Colors.grey[400],
                ),
              ),
      ),
    );
  }

  /// Parses markdown and returns plain text without formatting artifacts.
  String _stripMarkdown(String input) {
    final doc = md.Document(encodeHtml: false);
    final nodes = doc.parseLines(input.split('\n'));
    final buffer = StringBuffer();

    void writeNode(md.Node node) {
      if (node is md.Text) {
        buffer.write(node.text);
        return;
      }

      if (node is md.Element) {
        // Preserve spacing for block-level elements
        final isBlock = const {
          'p',
          'li',
          'ul',
          'ol',
          'blockquote',
          'h1',
          'h2',
          'h3',
          'h4',
          'h5',
          'h6',
        }.contains(node.tag);

        if (node.tag == 'br') buffer.write('\n');

        for (final child in node.children ?? []) {
          writeNode(child);
        }

        if (isBlock) buffer.write('\n');
      }
    }

    for (final node in nodes) {
      writeNode(node);
    }

    return buffer.toString().replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  Widget _buildSkeleton(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        enabled: true,
        child: Container(
          // Match actual content: vertical: 6, not 8
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.6),
            border: Border.all(
              color: Theme.of(
                context,
              ).colorScheme.outline.withValues(alpha: 0.2),
              width: 1,
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // App icon skeleton
              Container(
                width: 58,
                height: 58,
                decoration: BoxDecoration(
                  color: AppColors.darkSkeletonBase,
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
              const SizedBox(width: 18),
              // App details skeleton
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // App name skeleton
                    Row(
                      children: [
                        Container(
                          height: 24,
                          width: 150,
                          decoration: BoxDecoration(
                            color: AppColors.darkSkeletonBase,
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                        const Spacer(),
                        Container(
                          height: 20,
                          width: 60,
                          decoration: BoxDecoration(
                            color: AppColors.darkSkeletonBase,
                            borderRadius: BorderRadius.circular(10),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    // Description skeleton - 3 lines to match actual maxLines: 3
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Container(
                          height: 16,
                          width: double.infinity,
                          decoration: BoxDecoration(
                            color: AppColors.darkSkeletonBase,
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Container(
                          height: 16,
                          width: double.infinity,
                          decoration: BoxDecoration(
                            color: AppColors.darkSkeletonBase,
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Container(
                          height: 16,
                          width: 140,
                          decoration: BoxDecoration(
                            color: AppColors.darkSkeletonBase,
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                      ],
                    ),
                    // Author skeleton (only if showSignedBy is true) - match Gap(14) spacing
                    if (showSignedBy) ...[
                      const SizedBox(height: 14),
                      Row(
                        children: [
                          CircleAvatar(
                            radius: 12,
                            backgroundColor: AppColors.darkSkeletonBase,
                          ),
                          const SizedBox(width: 8),
                          Container(
                            height: 16,
                            width: 120,
                            decoration: BoxDecoration(
                              color: AppColors.darkSkeletonBase,
                              borderRadius: BorderRadius.circular(4),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AppCardUpdateButtonSection extends ConsumerWidget {
  const _AppCardUpdateButtonSection({required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final downloadInfo = ref.watch(downloadInfoProvider(app.identifier));
    final hasDownload = downloadInfo != null;
    final shouldShow = app.hasUpdate || hasDownload;

    if (!shouldShow) return const SizedBox.shrink();

    return Column(
      children: [
        const Gap(12),
        SizedBox(
          height: 38,
          child: _CompactInstallButton(
            app: app,
            release: app.latestRelease.value,
          ),
        ),
      ],
    );
  }
}

class _AppCardZapEncouragementSection extends ConsumerWidget {
  const _AppCardZapEncouragementSection({
    required this.app,
    required this.publisher,
  });

  final App app;
  final Profile? publisher;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final downloadInfo = ref.watch(downloadInfoProvider(app.identifier));

    final isActive = downloadInfo?.isActiveOrInstalling ?? false;
    final hasLud16 = publisher?.lud16?.trim().isNotEmpty ?? false;
    final canZap = publisher != null && hasLud16;
    final shouldShow = isActive && !app.isRelaySigned && canZap;

    if (!shouldShow) return const SizedBox.shrink();

    return Column(
      children: [
        const Gap(12),
        _ZapEncouragementInCard(app: app, author: publisher),
      ],
    );
  }
}

/// Compact wrapper around InstallButton for use in app cards
class _CompactInstallButton extends ConsumerWidget {
  final App app;
  final Release? release;

  const _CompactInstallButton({required this.app, this.release});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return InstallButton(app: app, release: release, compact: true);
  }
}

/// Zap encouragement widget shown inside app cards during installation
class _ZapEncouragementInCard extends HookConsumerWidget {
  final App app;
  final Profile? author;

  const _ZapEncouragementInCard({required this.app, required this.author});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return InkWell(
      onTap: () => showZapDialog(context, ref, app, author),
      borderRadius: BorderRadius.circular(10),
      child: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: Colors.orange.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: Colors.orange.withValues(alpha: 0.2),
            width: 1,
          ),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                'Enjoying ${app.name ?? 'this app'}? Zap it!',
                style: Theme.of(
                  context,
                ).textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w600),
              ),
            ),
            const SizedBox(width: 8),
            SizedBox(
              height: 28,
              child: TextButton(
                onPressed: () => showZapDialog(context, ref, app, author),
                style: TextButton.styleFrom(
                  backgroundColor: Colors.orange,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 4,
                  ),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(6),
                  ),
                ),
                child: const Text(
                  'Zap',
                  style: TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
