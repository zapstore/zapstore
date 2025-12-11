import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:go_router/go_router.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:background_downloader/background_downloader.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/services/download_service.dart';
import 'package:zapstore/services/profile_service.dart';
import 'package:zapstore/widgets/zap_widgets.dart';

import 'author_container.dart';
import 'version_pill_widget.dart';
import 'install_button.dart';
import '../theme.dart';

class AppCard extends HookConsumerWidget {
  final App? app;
  final Profile? author;
  final bool isLoading;
  final bool showUpdateArrow;
  final bool showSignedBy;
  final bool showUpdateButton;
  final bool showZapEncouragement;

  const AppCard({
    super.key,
    this.app,
    this.author,
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

    // Use provided author, or try relationship, or fallback query
    final relationshipAuthor = app!.author.value;

    // Fallback: Query author profile directly if no author provided and relationship doesn't work
    final authorPubkey = app!.event.pubkey;
    final needsFallbackQuery = author == null && relationshipAuthor == null;
    final authorProfileAsync = needsFallbackQuery
        ? ref.watch(profileProvider(authorPubkey))
        : null;
    final fallbackAuthor = authorProfileAsync?.value;

    // Use the working author (provided, relationship, or fallback)
    final actualAuthor = author ?? relationshipAuthor ?? fallbackAuthor;

    return GestureDetector(
      onTap: () {
        final segments = GoRouterState.of(context).uri.pathSegments;
        final first = segments.isNotEmpty ? segments.first : 'search';
        context.push('/$first/app/${app!.id}', extra: app!);
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
                  // App Name and Version Row
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

                  const SizedBox(height: 12),

                  // App Description with improved readability
                  Text(
                    app!.description.isNotEmpty
                        ? app!.description
                        : 'No description available',
                    style: context.textTheme.bodyMedium?.copyWith(
                      height: 1.5,
                      color: AppColors.darkOnSurfaceSecondary,
                    ),
                    overflow: TextOverflow.ellipsis,
                    maxLines: 3,
                    softWrap: true,
                  ),

                  // Author signature (only if app has publisher)
                  if (showSignedBy && actualAuthor != null) ...[
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
                        profile: actualAuthor,
                        beforeText: 'Published by',
                        size: context.textTheme.bodyMedium!.fontSize!,
                        oneLine: true,
                        app: app,
                      ),
                    ),
                  ],

                  // Update button (for apps with updates or currently downloading/installing)
                  if (showUpdateButton) ...[
                    Builder(
                      builder: (context) {
                        final downloadInfo = ref.watch(
                          downloadInfoProvider(app!.identifier),
                        );
                        final hasDownload = downloadInfo != null;
                        final shouldShow = app!.hasUpdate || hasDownload;

                        if (!shouldShow) return const SizedBox.shrink();

                        return Column(
                          children: [
                            Gap(12),
                            SizedBox(
                              height: 38,
                              child: _CompactInstallButton(
                                app: app!,
                                release: app!.latestRelease.value,
                              ),
                            ),
                          ],
                        );
                      },
                    ),
                  ],

                  // Zap encouragement (only for downloading/installing developer-signed apps)
                  if (showZapEncouragement) ...[
                    Builder(
                      builder: (context) {
                        final downloadInfo = ref.watch(
                          downloadInfoProvider(app!.identifier),
                        );

                        // Show during download OR installation (including paused)
                        final isActive =
                            downloadInfo != null &&
                            (downloadInfo.isInstalling ||
                                downloadInfo.status == TaskStatus.running ||
                                downloadInfo.status == TaskStatus.enqueued ||
                                downloadInfo.status == TaskStatus.paused ||
                                downloadInfo.status ==
                                    TaskStatus.waitingToRetry);

                        final actualAuthor = author ?? app!.author.value;
                        final hasLud16 =
                            actualAuthor?.lud16?.trim().isNotEmpty ?? false;
                        final canZap = actualAuthor != null && hasLud16;
                        final shouldShow =
                            isActive && !app!.isRelaySigned && canZap;

                        if (!shouldShow) return const SizedBox.shrink();

                        return Column(
                          children: [
                            Gap(12),
                            _ZapEncouragementInCard(
                              app: app!,
                              author: actualAuthor,
                            ),
                          ],
                        );
                      },
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppIcon(BuildContext context) {
    final iconUrl = app!.icons.isNotEmpty ? app!.icons.first : null;

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

  Widget _buildSkeleton(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        enabled: true,
        child: Container(
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
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
                    // Description skeleton
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
                        const SizedBox(height: 4),
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
                    // Author skeleton (only if showSignedBy is true)
                    if (showSignedBy) ...[
                      const SizedBox(height: 12),
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
