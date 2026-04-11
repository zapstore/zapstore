import 'dart:convert';

import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nostr_route.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/comments_section.dart';
import 'package:zapstore/widgets/common/badges.dart';
import 'package:zapstore/widgets/common/time_utils.dart';
import 'package:zapstore/widgets/floating_overflow_menu.dart';
import 'package:zapstore/theme.dart';

class AppStackScreen extends HookConsumerWidget {
  const AppStackScreen({super.key, required this.stackId, this.authorPubkey});

  final String stackId;
  final String? authorPubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stackState = ref.watch(
      query<AppStack>(
        authors: authorPubkey != null ? {authorPubkey!} : null,
        tags: {
          '#d': {stackId},
        },
        limit: 1,
        source: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
        ),
        subscriptionPrefix: 'app-stack-detail-$stackId',
      ),
    );

    if (stackState case StorageError(:final exception)) {
      return _ErrorScaffold(message: exception.toString());
    }

    final stack = stackState.models.firstOrNull;

    if (stack == null) {
      // Still loading — show skeleton
      if (stackState is StorageLoading) {
        return Scaffold(
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: _AppStackSkeleton(),
          ),
        );
      }
      // Loading complete but stack not found
      return _NotFoundScaffold(stackId: stackId);
    }

    return _AppStackContentWithApps(stack: stack);
  }
}

/// Intermediate widget that loads apps with their release relationships
class _AppStackContentWithApps extends HookConsumerWidget {
  final AppStack stack;

  const _AppStackContentWithApps({required this.stack});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isEncrypted = stack.content.isNotEmpty;

    // For encrypted stacks, decrypt content to get app IDs
    final decryptedAppIds = useState<Set<String>?>(null);
    final decryptError = useState<String?>(null);

    useEffect(() {
      if (!isEncrypted) return null;

      Future<void> decrypt() async {
        final signer = ref.read(Signer.activeSignerProvider);
        final pubkey = ref.read(Signer.activePubkeyProvider);

        if (signer == null || pubkey == null) {
          decryptError.value = 'Sign in required to view this stack';
          return;
        }

        try {
          final decrypted = await signer.nip44Decrypt(stack.content, pubkey);
          final ids = (jsonDecode(decrypted) as List).cast<String>().toSet();
          decryptedAppIds.value = ids;
        } catch (e) {
          decryptError.value = 'Failed to decrypt stack';
        }
      }

      decrypt();
      return null;
    }, [stack.content]);

    // Handle decrypt error
    if (decryptError.value != null) {
      return _AppStackContent(
        stack: stack,
        apps: const [],
        errorMessage: decryptError.value,
      );
    }

    // For encrypted stacks, wait for decryption
    if (isEncrypted && decryptedAppIds.value == null) {
      return Scaffold(
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: _AppStackSkeleton(),
        ),
      );
    }

    // Get app addressable IDs from either tags (public) or decrypted content (private)
    final appAddressableIds = isEncrypted
        ? decryptedAppIds.value!
        : stack.event
            .getTagSetValues('a')
            .where((id) => id.startsWith('32267:'))
            .toSet();

    if (appAddressableIds.isEmpty) {
      return _AppStackContent(stack: stack, apps: const []);
    }

    final authors = <String>{};
    final identifiers = <String>{};
    for (final id in appAddressableIds) {
      final parts = id.split(':');
      if (parts.length >= 3) {
        authors.add(parts[1]);
        identifiers.add(parts.skip(2).join(':'));
      }
    }

    final appsState = ref.watch(
      query<App>(
        authors: authors,
        tags: {
          '#d': identifiers,
          '#f': {'android-arm64-v8a'},
        },
        and: (app) => {
          app.latestRelease.query(
            and: (release) => {
              release.latestMetadata.query(),
              release.latestAsset.query(),
            },
          ),
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-stack-apps-${stack.identifier}',
      ),
    );

    // Key by addressable ID and preserve the original stack order
    final appsMap = {for (final app in appsState.models) app.id: app};
    final orderedApps = appAddressableIds
        .map((id) => appsMap[id])
        .whereType<App>()
        .toList();

    return _AppStackContent(stack: stack, apps: orderedApps);
  }
}

class _ErrorScaffold extends StatelessWidget {
  final String message;
  const _ErrorScaffold({required this.message});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('App Stack')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline, size: 64),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Text(message, textAlign: TextAlign.center),
            ),
          ],
        ),
      ),
    );
  }
}

class _NotFoundScaffold extends StatelessWidget {
  final String stackId;
  const _NotFoundScaffold({required this.stackId});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('App Stack')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(
                Icons.apps_outlined,
                size: 64,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              const SizedBox(height: 16),
              Text(
                'Stack not found',
                style: Theme.of(context).textTheme.titleLarge,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              Text(
                'This stack may have been deleted or is not available',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Internal widget that displays stack details
class _AppStackContent extends HookConsumerWidget {
  final AppStack stack;
  final List<App> apps;
  final String? errorMessage;

  const _AppStackContent({
    required this.stack,
    required this.apps,
    this.errorMessage,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query author profile from social relays
    final authorState = ref.watch(
      query<Profile>(
        authors: {stack.pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
        subscriptionPrefix: 'app-stack-profile',
      ),
    );
    final author = authorState.models.firstOrNull;
    final isAuthorLoading = authorState is StorageLoading && author == null;

    // Sort apps: uninstalled first, keeping original order otherwise
    final packageManager = ref.watch(packageManagerProvider.notifier);
    final sortedApps = _sortAppsUninstalledFirst(apps, packageManager);
    final totalApps = sortedApps.length;

    return Scaffold(
      body: SafeArea(
        child: Stack(
          children: [
            SingleChildScrollView(
              padding: const EdgeInsets.only(top: 16, bottom: 32),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Stack header
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: _StackHeader(
                      stack: stack,
                      author: author,
                      isAuthorLoading: isAuthorLoading,
                    ),
                  ),
                  const SizedBox(height: 24),
                  // Apps section header with count badge
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Row(
                      children: [
                        Text(
                          'Apps in this stack',
                          style: context.textTheme.titleLarge,
                        ),
                        const SizedBox(width: 8),
                        CountBadge(
                          count: totalApps,
                          color: AppColors.darkPillBackground,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  // Apps list
                  if (sortedApps.isEmpty)
                    _EmptyAppsPlaceholder()
                  else
                    ...sortedApps.map(
                      (app) => AppCard(app: app, showUpdateArrow: app.hasUpdate),
                    ),
                  // Comments section - hidden for private/encrypted stacks
                  if (stack.content.isEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 24),
                      child: StackCommentsSection(stack: stack),
                    ),
                ],
              ),
            ),
            FloatingOverflowMenu(
              shareUrl: getStackShareUrl(stack),
              publisherPubkey: stack.pubkey,
            ),
          ],
        ),
      ),
    );
  }

  /// Sort apps so uninstalled ones come first, keeping original order otherwise
  List<App> _sortAppsUninstalledFirst(
    List<App> apps,
    PackageManager packageManager,
  ) {
    final uninstalled = <App>[];
    final installed = <App>[];

    for (final app in apps) {
      if (packageManager.isInstalled(app.identifier)) {
        installed.add(app);
      } else {
        uninstalled.add(app);
      }
    }

    return [...uninstalled, ...installed];
  }
}

/// Stack header with name and author
class _StackHeader extends StatelessWidget {
  const _StackHeader({
    required this.stack,
    this.author,
    this.isAuthorLoading = false,
  });

  final AppStack stack;
  final Profile? author;
  final bool isAuthorLoading;

  bool get _isEncrypted => stack.content.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final subtitleColor = Theme.of(context).colorScheme.onSurfaceVariant;
    final subtitleStyle = Theme.of(context).textTheme.bodySmall?.copyWith(
          color: subtitleColor,
        );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Stack name with padlock for private stacks
        Row(
          children: [
            Flexible(
              child: Text(
                stack.name ?? stack.identifier,
                style: context.textTheme.headlineMedium,
              ),
            ),
            if (_isEncrypted) ...[
              const SizedBox(width: 8),
              Icon(
                Icons.lock,
                size: 20,
                color: Theme.of(context).colorScheme.onSurface,
              ),
            ],
          ],
        ),
        const SizedBox(height: 8),
        // Published by author - always show, with fallback to npub
        AuthorContainer(
          profile: author,
          pubkey: stack.pubkey,
          beforeText: 'Published by',
          oneLine: true,
          size: 14,
          isLoading: isAuthorLoading,
          onTap: () => pushUser(context, stack.pubkey),
        ),
        const SizedBox(height: 4),
        // Metadata row: updated timestamp + private indicator
        Row(
          children: [
            Icon(Icons.update, size: 14, color: subtitleColor),
            const SizedBox(width: 4),
            Text('Updated ', style: subtitleStyle),
            TimeAgoText(stack.event.createdAt, style: subtitleStyle),
            if (_isEncrypted) ...[
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: subtitleColor.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.lock_outline, size: 12, color: subtitleColor),
                    const SizedBox(width: 4),
                    Text(
                      'Private',
                      style: subtitleStyle?.copyWith(fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
        if (stack.description case final description?) ...[
          const SizedBox(height: 12),
          Text(
            description,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ],
      ],
    );
  }
}

/// Empty state when no apps in stack
class _EmptyAppsPlaceholder extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(32),
      child: Column(
        children: [
          Icon(
            Icons.apps_outlined,
            size: 48,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
          const SizedBox(height: 12),
          Text(
            'No apps in this stack',
            style: context.textTheme.bodyLarge?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

/// Skeleton loading state for the stack screen
class _AppStackSkeleton extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header skeleton
            Container(
              height: 28,
              width: 180,
              decoration: BoxDecoration(
                color: AppColors.darkSkeletonBase,
                borderRadius: BorderRadius.circular(4),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Container(
                  width: 28,
                  height: 28,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  height: 16,
                  width: 100,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            // Section header with count badge
            Row(
              children: [
                Container(
                  height: 20,
                  width: 140,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  height: 20,
                  width: 24,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            // App cards skeleton (using AppCard skeleton)
            ...List.generate(3, (_) => const AppCard(isLoading: true)),
          ],
        ),
      ),
    );
  }
}
