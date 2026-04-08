import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:markdown/markdown.dart' as md;
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nostr_route.dart';
import 'package:zapstore/utils/url_utils.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import '../theme.dart';

/// Lightweight app card for search results.
/// No version pill, no profile fetch — just icon, name, description, and install status.
class SearchAppCard extends ConsumerWidget {
  final App? app;
  final bool isLoading;

  const SearchAppCard({super.key, this.app, this.isLoading = false});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (isLoading || app == null) {
      return _buildSkeleton(context);
    }

    final isInstalled =
        ref.watch(installedPackageProvider(app!.identifier)) != null;

    final iconUrl = firstValidHttpUrl(app!.icons);
    const iconSize = 48.0;

    return GestureDetector(
      onTap: () => pushApp(
        context,
        app!.identifier,
        author: app!.pubkey,
        kind: app!.event.kind,
      ),
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
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // App icon
            Container(
              width: iconSize,
              height: iconSize,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.1),
                    blurRadius: 8,
                    offset: const Offset(0, 2),
                  ),
                ],
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: iconUrl != null
                    ? CachedNetworkImage(
                        imageUrl: iconUrl,
                        fit: BoxFit.cover,
                        fadeInDuration: const Duration(milliseconds: 500),
                        fadeOutDuration: const Duration(milliseconds: 200),
                        placeholder: (_, __) => const SizedBox.shrink(),
                        errorWidget: (_, __, ___) => Center(
                          child: Icon(
                            Icons.broken_image_outlined,
                            size: 24,
                            color: Colors.grey[400],
                          ),
                        ),
                      )
                    : Center(
                        child: Icon(
                          Icons.apps_outlined,
                          size: 24,
                          color: Colors.grey[400],
                        ),
                      ),
              ),
            ),
            const SizedBox(width: 14),
            // Name + description
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Expanded(
                        child: Text(
                          app!.name ?? app!.identifier,
                          style: context.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w900,
                            fontSize:
                                (context.textTheme.titleMedium?.fontSize ??
                                    16) *
                                1.15,
                          ),
                          overflow: TextOverflow.ellipsis,
                          maxLines: 1,
                        ),
                      ),
                      if (isInstalled) ...[
                        const SizedBox(width: 8),
                        Icon(
                          Icons.download_done_rounded,
                          size: 18,
                          color: Colors.grey[500],
                        ),
                      ],
                    ],
                  ),
                  if (app!.description.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(
                      _stripMarkdown(app!.description),
                      style: context.textTheme.bodyMedium?.copyWith(
                        height: 1.4,
                        color: AppColors.darkOnSurfaceSecondary,
                      ),
                      overflow: TextOverflow.ellipsis,
                      maxLines: 2,
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
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
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
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: AppColors.darkSkeletonBase,
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      height: 20,
                      width: 160,
                      decoration: BoxDecoration(
                        color: AppColors.darkSkeletonBase,
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Container(
                      height: 14,
                      width: double.infinity,
                      decoration: BoxDecoration(
                        color: AppColors.darkSkeletonBase,
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                    const SizedBox(height: 6),
                    Container(
                      height: 14,
                      width: 200,
                      decoration: BoxDecoration(
                        color: AppColors.darkSkeletonBase,
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
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
