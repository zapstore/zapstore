import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:zapstore/utils/extensions.dart';
import '../theme.dart';

import '../widgets/common/note_parser.dart';
import '../widgets/app_card.dart';
import '../widgets/profile_header.dart';
import '../widgets/zap_widgets.dart';
import '../services/notification_service.dart';
import '../services/profile_service.dart';

/// Developer profile and apps screen
class DeveloperScreen extends HookConsumerWidget {
  const DeveloperScreen({super.key, required this.pubkey});

  final String pubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final scrollController = useScrollController();

    // Load developer profile
    final profileAsync = ref.watch(profileProvider(pubkey));
    final profile = profileAsync.value;

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            // Developer header - always show, with skeleton if loading
            _DeveloperHeader(
              profile: profile,
              isLoading: profileAsync.isLoading,
              hasError: profileAsync.hasError,
            ),

            // Zaps list for all developer's apps
            _DeveloperZapsList(pubkey: pubkey),

            // Tabs for apps and info
            Expanded(
              child: DefaultTabController(
                length: 2,
                child: Column(
                  children: [
                    const TabBar(
                      tabs: [
                        Tab(text: 'Apps'),
                        Tab(text: 'About'),
                      ],
                    ),
                    Expanded(
                      child: TabBarView(
                        children: [
                          _DeveloperAppsTab(
                            pubkey: pubkey,
                            scrollController: scrollController,
                          ),
                          _DeveloperAboutTab(
                            profile: profile,
                            isLoading: profileAsync.isLoading,
                            hasError: profileAsync.hasError,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DeveloperHeader extends HookConsumerWidget {
  const _DeveloperHeader({
    required this.profile,
    this.isLoading = false,
    this.hasError = false,
  });

  final Profile? profile;
  final bool isLoading;
  final bool hasError;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Show skeleton when loading or error
    if (isLoading || hasError || profile == null) {
      return ProfileHeader(isLoading: true, radius: 40);
    }

    return ProfileHeader(profile: profile, radius: 40);
  }
}

class _DeveloperAppsTab extends HookConsumerWidget {
  const _DeveloperAppsTab({
    required this.pubkey,
    required this.scrollController,
  });

  final String pubkey;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query apps by this specific developer
    final appsState = ref.watch(
      query<App>(
        authors: {pubkey}, // Filter by developer pubkey
        limit: 100,
        tags: {
          '#f': {'android-arm64-v8a'},
        },
        and: (app) => {app.latestRelease},
        subscriptionPrefix: 'developer-apps',
      ),
    );

    // Query author profile from 'social' relay group
    final authorAsync = ref.watch(profileProvider(pubkey));
    final author = authorAsync.value;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Apps List
        Expanded(
          child: switch (appsState) {
            StorageLoading() => ListView.builder(
              padding: const EdgeInsets.all(16),
              itemCount: 5,
              itemBuilder: (context, index) => AppCard(isLoading: true),
            ),
            StorageError() => _buildErrorState(
              context,
              appsState.toString(),
            ),
            StorageData(:final models) when models.isEmpty => _buildEmptyState(context),
            StorageData(:final models) => _buildAppsList(
              context,
              models,
              author,
            ),
          },
        ),
      ],
    );
  }

  Widget _buildErrorState(BuildContext context, String error) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.error_outline, size: 48, color: Colors.red[400]),
            const SizedBox(height: 16),
            Text('Error loading apps', style: context.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(
              error,
              style: context.textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmptyState(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.apps_outlined, size: 48, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text('No apps published', style: context.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(
              'This developer hasn\'t published any apps yet',
              style: context.textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppsList(
    BuildContext context,
    List<App> apps,
    Profile? author,
  ) {
    return ListView.builder(
      padding: const EdgeInsets.all(16),
      itemCount: apps.length,
      itemBuilder: (context, index) {
        final app = apps[index];
        return Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: AppCard(app: app, author: author, showSignedBy: false),
        );
      },
    );
  }
}

class _DeveloperZapsList extends HookConsumerWidget {
  const _DeveloperZapsList({required this.pubkey});

  final String pubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query all apps by this developer with their latest metadata
    final appsState = ref.watch(
      query<App>(
        authors: {pubkey},
        tags: {
          '#f': {'android-arm64-v8a'},
        },
        limit: 100,
        and: (app) => {app.latestRelease},
        source: LocalAndRemoteSource(stream: false, background: true),
        subscriptionPrefix: 'developer-all-apps',
      ),
    );

    // Get all apps
    final apps = appsState is StorageData ? appsState.models : <App>[];

    if (apps.isEmpty) {
      return const SizedBox.shrink();
    }

    // Create combined tag maps for:
    // 1. App addressable IDs (for zaps on apps)
    // 2. FileMetadata event IDs (for zaps on metadata)
    final allAppTags = <String, Set<String>>{};
    final metadataIds = <String>{};

    for (final app in apps) {
      // Add app addressable ID tags
      final appTags = app.event.addressableIdTagMap;
      for (final entry in appTags.entries) {
        allAppTags[entry.key] = {...?allAppTags[entry.key], ...entry.value};
      }

      // Add metadata event IDs
      final metadata = app.latestFileMetadata;
      if (metadata != null) {
        metadataIds.add(metadata.id);
      }
    }

    // Query zaps on app addressable IDs
    final zapsOnAppsState = ref.watch(
      query<Zap>(
        tags: allAppTags,
        source: LocalAndRemoteSource(relays: 'social'),
        and: (zap) => {zap.author, zap.zapRequest},
        andSource: LocalAndRemoteSource(relays: 'social', stream: false),
        subscriptionPrefix: 'developer-zaps-apps',
      ),
    );

    // Query zaps on file metadata
    final zapsOnMetadataState = metadataIds.isNotEmpty
        ? ref.watch(
            query<Zap>(
              tags: {'#e': metadataIds},
              source: LocalAndRemoteSource(relays: 'social'),
              and: (zap) => {zap.author, zap.zapRequest},
              andSource: LocalAndRemoteSource(relays: 'social', stream: false),
              subscriptionPrefix: 'developer-zaps-metadata',
            ),
          )
        : null;

    // Combine all zaps
    final allZaps = <Zap>{
      if (zapsOnAppsState is StorageData) ...zapsOnAppsState.models,
      if (zapsOnMetadataState != null && zapsOnMetadataState is StorageData)
        ...zapsOnMetadataState.models,
    };

    if (allZaps.isEmpty) {
      return const SizedBox.shrink();
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: ZappersHorizontalList(zaps: allZaps.toList()),
    );
  }
}

class _DeveloperAboutTab extends StatelessWidget {
  const _DeveloperAboutTab({
    required this.profile,
    this.isLoading = false,
    this.hasError = false,
  });

  final Profile? profile;
  final bool isLoading;
  final bool hasError;

  @override
  Widget build(BuildContext context) {
    // Show skeleton when loading or profile is null
    if (isLoading || hasError || profile == null) {
      return SkeletonizerConfig(
        data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
        child: Skeletonizer(
          enabled: true,
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              // Bio section skeleton
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        height: 20,
                        width: 100,
                        decoration: BoxDecoration(
                          color: AppColors.darkSkeletonBase,
                          borderRadius: BorderRadius.circular(4),
                        ),
                      ),
                      const SizedBox(height: 12),
                      Container(
                        height: 16,
                        width: double.infinity,
                        decoration: BoxDecoration(
                          color: AppColors.darkSkeletonBase,
                          borderRadius: BorderRadius.circular(4),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 16),

              // Contact section skeleton
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        height: 20,
                        width: 100,
                        decoration: BoxDecoration(
                          color: AppColors.darkSkeletonBase,
                          borderRadius: BorderRadius.circular(4),
                        ),
                      ),
                      const SizedBox(height: 12),
                      const ListTile(
                        leading: Icon(Icons.verified),
                        title: Text('Loading...'),
                        subtitle: Text('---'),
                        contentPadding: EdgeInsets.zero,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // Bio section
        if (profile!.about != null && profile!.about!.isNotEmpty) ...[
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: NoteParser.parse(
                context,
                profile!.about!,
                textStyle: context.textTheme.bodyMedium,
                onNostrEntity: (entity) => NostrEntityWidget(
                  entity: entity,
                  colorPair: [
                    Theme.of(context).colorScheme.primary,
                    Theme.of(context).colorScheme.secondary,
                  ],
                  onProfileTap: (pubkey) {
                    final segments = GoRouterState.of(context).uri.pathSegments;
                    final first = segments.isNotEmpty
                        ? segments.first
                        : 'search';
                    context.push('/$first/developer/$pubkey');
                  },
                  onHashtagTap: (hashtag) => context.push('/search?q=$hashtag'),
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
        ],

        // Contact info
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // npub
                ListTile(
                  leading: const Icon(Icons.share),
                  title: const Text('npub'),
                  subtitle: Text(
                    '${Utils.encodeShareableFromString(profile!.pubkey, type: 'npub').substring(0, 16)}...',
                  ),
                  trailing: IconButton(
                    icon: const Icon(Icons.copy),
                    onPressed: () {
                      final npub = Utils.encodeShareableFromString(
                        profile!.pubkey,
                        type: 'npub',
                      );
                      Clipboard.setData(ClipboardData(text: npub));
                      context.showInfo('npub copied to clipboard');
                    },
                  ),
                  contentPadding: EdgeInsets.zero,
                ),

                // NIP-05 identifier
                if (profile!.nip05 != null) ...[
                  ListTile(
                    leading: const Icon(Icons.verified),
                    title: const Text('NIP-05 Verified'),
                    subtitle: Text(profile!.nip05!),
                    contentPadding: EdgeInsets.zero,
                    onTap: () {
                      Clipboard.setData(ClipboardData(text: profile!.nip05!));
                      context.showInfo('NIP-05 copied to clipboard');
                    },
                  ),
                ],

                // Lightning address
                if (profile!.lud16?.trim().isNotEmpty ?? false) ...[
                  ListTile(
                    leading: const Icon(Icons.bolt),
                    title: const Text('Lightning Address'),
                    subtitle: Text(profile!.lud16!),
                    contentPadding: EdgeInsets.zero,
                    onTap: () {
                      Clipboard.setData(ClipboardData(text: profile!.lud16!));
                      context.showInfo('Lightning address copied to clipboard');
                    },
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}
