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
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/widgets/zap_widgets.dart';

import 'common/profile_avatar.dart';
import 'common/profile_name_widget.dart';
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
  final bool showDescription;

  const AppCard({
    super.key,
    this.app,
    this.isLoading = false,
    this.showUpdateArrow = false,
    this.showSignedBy = true,
    this.showUpdateButton = false,
    this.showZapEncouragement = false,
    this.showDescription = true,
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

    Widget buildCard(Profile? publisher, bool isPublisherLoading) => GestureDetector(
      onTap: () {
        final segments = GoRouterState.of(context).uri.pathSegments;
        final first = segments.isNotEmpty ? segments.first : 'search';
        // Prefer naddr so the detail screen can uniquely identify the app
        // (identifier + author) and not accidentally resolve to a different
        // publisher's app with the same identifier.
        final naddr = Utils.encodeShareableIdentifier(
          AddressInput(
            identifier: app!.identifier,
            author: app!.pubkey,
            kind: app!.event.kind,
            relays: const [],
          ),
        );
        context.push('/$first/app/$naddr');
      },
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(20),
          color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.6),
          border: Border.all(
            color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
            width: 1,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header row: Icon + Name/Version (icon matches header height)
            LayoutBuilder(
              builder: (context, constraints) {
                // Icon takes a little over 20% of available width
                final iconSize = (constraints.maxWidth * 0.21).clamp(
                  50.0,
                  68.0,
                );
                return Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // App Icon (stretches to match name + version height, ~20% width)
                    _buildAppIcon(context, iconSize),

                    const SizedBox(width: 14),

                    // App Name and Version (always stacked)
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisAlignment: MainAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          // App name with optional "by publisher" inline
                          _buildAppNameWithPublisher(context, publisher, isPublisherLoading),
                          const SizedBox(height: 8),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: VersionPillWidget(
                              app: app!,
                              showUpdateArrow: showUpdateArrow,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                );
              },
            ),

            // App Description rendered as plain text (markdown stripped)
            if (showDescription) ...[
              const SizedBox(height: 10),
              Text(
                descriptionText,
                style: descriptionStyle,
                overflow: TextOverflow.ellipsis,
                maxLines: 2,
                softWrap: true,
              ),
            ],

            // Update button (for apps with updates or currently downloading/installing)
            if (showUpdateButton)
              LayoutBuilder(
                builder: (context, constraints) {
                  // Match the text column start (icon width + spacing)
                  final iconSize = (constraints.maxWidth * 0.21).clamp(
                    50.0,
                    68.0,
                  );
                  final leftInset = iconSize + 14;

                  return Padding(
                    padding: EdgeInsets.only(left: leftInset),
                    child: SizedBox(
                      width: (constraints.maxWidth - leftInset).clamp(
                        0.0,
                        constraints.maxWidth,
                      ),
                      child: _AppCardUpdateButtonSection(app: app!),
                    ),
                  );
                },
              ),

            // Zap encouragement (only for downloading/installing developer-signed apps)
            if (showZapEncouragement)
              _AppCardZapEncouragementSection(app: app!, publisher: publisher),
          ],
        ),
      ),
    );

    if (!needsPublisher) return buildCard(null, false);

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

        final publisher = publisherState.models.firstOrNull;
        final isPublisherLoading =
            publisherState is StorageLoading && publisher == null;

        return buildCard(publisher, isPublisherLoading);
      },
    );
  }

  Widget _buildAppIcon(BuildContext context, double size) {
    final iconUrl = firstValidHttpUrl(app!.icons);

    return SizedBox(
      width: size,
      height: size,
      child: Container(
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
      ),
    );
  }

  Widget _buildAppNameWithPublisher(
    BuildContext context,
    Profile? publisher,
    bool isPublisherLoading,
  ) {
    final appName = app!.name ?? app!.identifier;
    final titleStyle = context.textTheme.titleMedium?.copyWith(
      fontWeight: FontWeight.w900,
    );

    // If not showing signed by or relay-signed, just show name
    if (!showSignedBy || app!.isRelaySigned) {
      return Text(
        appName,
        style: titleStyle,
        overflow: TextOverflow.ellipsis,
        maxLines: 1,
      );
    }

    // Show "(app name) by (profile)" with wrapping
    final byStyle = context.textTheme.bodyMedium?.copyWith(
      color: AppColors.darkOnSurfaceSecondary,
    );
    final publisherStyle = byStyle?.copyWith(fontWeight: FontWeight.w600);
    final avatarSize = context.textTheme.bodyMedium!.fontSize! * 1.4;

    return Text.rich(
      TextSpan(
        children: [
          TextSpan(text: appName, style: titleStyle),
          TextSpan(text: '  by ', style: byStyle),
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: Padding(
              padding: const EdgeInsets.only(right: 4),
              child: SizedBox(
                width: avatarSize,
                height: avatarSize,
                child: ProfileAvatar(
                  profile: publisher,
                  radius: avatarSize / 2,
                ),
              ),
            ),
          ),
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: ProfileNameWidget(
              pubkey: app!.event.pubkey,
              profile: publisher,
              isLoading: isPublisherLoading,
              style: publisherStyle,
              skeletonWidth: 80,
            ),
          ),
        ],
      ),
      softWrap: true,
      overflow: TextOverflow.visible,
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
          // Match actual content: vertical: 4
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
          padding: const EdgeInsets.all(12),
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
          child: LayoutBuilder(
            builder: (context, constraints) {
              final iconSize = (constraints.maxWidth * 0.21).clamp(50.0, 68.0);
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Header row: Icon + Name/Version
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // App icon skeleton
                      Container(
                        width: iconSize,
                        height: iconSize,
                        decoration: BoxDecoration(
                          color: AppColors.darkSkeletonBase,
                          borderRadius: BorderRadius.circular(14),
                        ),
                      ),
                      const SizedBox(width: 14),
                      // App name and version skeleton (stacked)
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // App name skeleton (with "by publisher" space)
                            Container(
                              height: 20,
                              width: 180,
                              decoration: BoxDecoration(
                                color: AppColors.darkSkeletonBase,
                                borderRadius: BorderRadius.circular(4),
                              ),
                            ),
                            const SizedBox(height: 8),
                            // Version pill skeleton
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
                      ),
                    ],
                  ),
                  // Description skeleton - 2 lines to match maxLines: 2
                  if (showDescription) ...[
                    const SizedBox(height: 10),
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
                        const SizedBox(height: 8),
                        Container(
                          height: 16,
                          width: 200,
                          decoration: BoxDecoration(
                            color: AppColors.darkSkeletonBase,
                            borderRadius: BorderRadius.circular(4),
                          ),
                        ),
                      ],
                    ),
                  ],
                ],
              );
            },
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
    final operation = ref.watch(installOperationProvider(app.identifier));
    final hasOperation = operation != null;
    final shouldShow = app.hasUpdate || hasOperation;

    if (!shouldShow) return const SizedBox.shrink();

    return Column(
      children: [
        const Gap(10),
        ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 38),
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
    final operation = ref.watch(installOperationProvider(app.identifier));

    final isActive = operation?.isActive ?? false;
    final hasLud16 = publisher?.lud16?.trim().isNotEmpty ?? false;
    final canZap = publisher != null && hasLud16;
    final shouldShow = isActive && !app.isRelaySigned && canZap;

    if (!shouldShow) return const SizedBox.shrink();

    return Column(
      children: [
        const Gap(10),
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
            ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 28),
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
                child: Text(
                  'Zap',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
