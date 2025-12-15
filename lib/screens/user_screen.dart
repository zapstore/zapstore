import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/utils/extensions.dart';

import '../theme.dart';
import '../widgets/common/note_parser.dart';
import '../widgets/common/profile_avatar.dart';
import '../widgets/app_card.dart';
import '../widgets/app_pack_container.dart';
import '../widgets/zap_widgets.dart';

/// User profile screen - shows any user/developer profile
class UserScreen extends HookConsumerWidget {
  const UserScreen({super.key, required this.pubkey});

  final String pubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Load user profile
    final profileState = ref.watch(query<Profile>(
      authors: {pubkey},
      source: const LocalAndRemoteSource(
        relays: {'social', 'vertex'},
        cachedFor: Duration(hours: 2),
      ),
    ));
    final profile = profileState.models.firstOrNull;

    // Query user's apps
    final userAppsState = ref.watch(
      query<App>(
        authors: {pubkey},
        tags: {
          '#f': {'android-arm64-v8a'},
        },
        limit: 20,
        and: (app) => {
          app.latestRelease,
          app.latestRelease.value?.latestMetadata,
        },
        source: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
          background: true,
        ),
        subscriptionPrefix: 'user-apps',
      ),
    );
    
    // For Zapstore pubkey, only show Zapstore's own apps (not relay-signed ones)
    final apps = pubkey == kZapstorePubkey
        ? userAppsState.models.where((a) => a.isZapstoreApp).toList()
        : userAppsState.models;

    // Query user's app packs
    final appPacksState = ref.watch(
      query<AppPack>(
        authors: {pubkey},
        limit: 20,
        and: (pack) => {pack.apps},
        source: const LocalAndRemoteSource(
          stream: false,
          background: true,
          relays: 'social',
        ),
        andSource: const LocalAndRemoteSource(
          relays: 'AppCatalog',
          stream: false,
          background: true,
        ),
        subscriptionPrefix: 'user-packs',
      ),
    );
    final packs = appPacksState.models
        .where((p) => p.identifier != kAppBookmarksIdentifier)
        .where(
          (p) =>
              p.apps.toList().any((a) => a.name != null || a.identifier.isNotEmpty),
        )
        .toList()
      ..sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));

    return Scaffold(
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            // Header with avatar and name
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.only(top: 16),
                child: _UserHeader(profile: profile, pubkey: pubkey),
              ),
            ),

            // Zaps widget
            SliverToBoxAdapter(
              child: _UserZapsList(apps: apps),
            ),

            // Bio section with max height
            if (profile?.about != null && profile!.about!.isNotEmpty)
              SliverToBoxAdapter(
                child: _UserBio(profile: profile),
              ),

            // Apps section - only show if apps exist
            if (apps.isNotEmpty) ...[
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
                  child: Text(
                    'Apps',
                    style: context.textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
              SliverList(
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    final app = apps[index];
                    return AppCard(
                      app: app,
                      author: profile,
                      showSignedBy: false,
                    );
                  },
                  childCount: apps.length,
                ),
              ),
            ],

            // App packs section - only show if packs exist
            if (packs.isNotEmpty)
              for (final pack in packs) ...[
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
                    child: Text(
                      pack.name ?? pack.identifier,
                      style: context.textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                ),
                SliverToBoxAdapter(
                  child: AppsGrid(
                    apps: pack.apps
                        .toList()
                        .where((a) => a.name != null || a.identifier.isNotEmpty)
                        .toList(),
                  ),
                ),
              ],

            // Bottom padding
            const SliverToBoxAdapter(
              child: SizedBox(height: 32),
            ),
          ],
        ),
      ),
    );
  }
}

class _UserHeader extends StatelessWidget {
  const _UserHeader({
    required this.profile,
    required this.pubkey,
  });

  final Profile? profile;
  final String pubkey;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Row(
        children: [
          ProfileAvatar(profile: profile, radius: 40),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  profile?.nameOrNpub ??
                      '${Utils.encodeShareableFromString(pubkey, type: 'npub').substring(0, 12)}...',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                if (profile?.nip05 != null) ...[
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Icon(
                        Icons.verified,
                        size: 16,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                      const SizedBox(width: 4),
                      Expanded(
                        child: Text(
                          profile!.nip05!,
                          style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
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
    );
  }
}

class _UserZapsList extends HookConsumerWidget {
  const _UserZapsList({required this.apps});

  final List<App> apps;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (apps.isEmpty) {
      return const SizedBox.shrink();
    }

    // Create combined tag maps for zaps on apps and their metadata
    final allAppTags = <String, Set<String>>{};
    final metadataIds = <String>{};

    for (final app in apps) {
      final appTags = app.event.addressableIdTagMap;
      for (final entry in appTags.entries) {
        allAppTags[entry.key] = {...?allAppTags[entry.key], ...entry.value};
      }

      final metadata = app.latestFileMetadata;
      if (metadata != null) {
        metadataIds.add(metadata.id);
      }
    }

    final zapTags = <String, Set<String>>{
      ...allAppTags,
      if (metadataIds.isNotEmpty) '#e': metadataIds,
    };

    if (zapTags.isEmpty) {
      return const SizedBox.shrink();
    }

    final zapsState = ref.watch(
      query<Zap>(
        tags: zapTags,
        source: const LocalAndRemoteSource(relays: 'social'),
        and: (zap) => {zap.author, zap.zapRequest},
        andSource: const LocalAndRemoteSource(relays: 'social', stream: false),
        subscriptionPrefix: 'user-zaps',
      ),
    );

    final allZaps = zapsState.models;

    if (allZaps.isEmpty) {
      return const SizedBox.shrink();
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: ZappersHorizontalList(zaps: allZaps.toList()),
    );
  }
}

class _UserBio extends HookWidget {
  const _UserBio({required this.profile});

  final Profile profile;

  @override
  Widget build(BuildContext context) {
    final expanded = useState(false);
    const maxHeight = 120.0;

    bool isLikelyLong(String text) {
      final trimmed = text.trim();
      if (trimmed.isEmpty) return false;

      final wordCount = trimmed.split(RegExp(r'\s+')).length;
      final newlineCount = '\n'.allMatches(trimmed).length;
      final charCount = trimmed.length;

      return wordCount > 50 || newlineCount > 4 || charCount > 350;
    }

    final about = profile.about ?? '';
    final shouldCollapse = !expanded.value && isLikelyLong(about);

    final bioContent = NoteParser.parse(
      context,
      about,
      textStyle: Theme.of(context).textTheme.bodyMedium?.copyWith(
        height: 1.5,
        color: AppColors.darkOnSurfaceSecondary,
      ),
      linkStyle: Theme.of(context).textTheme.bodyMedium?.copyWith(
        height: 1.5,
        color: Theme.of(context).colorScheme.primary,
      ),
      onNostrEntity: (entity) => NostrEntityWidget(
        entity: entity,
        colorPair: [
          Theme.of(context).colorScheme.primary,
          Theme.of(context).colorScheme.secondary,
        ],
        onProfileTap: (pubkey) {
          final segments = GoRouterState.of(context).uri.pathSegments;
          final first = segments.isNotEmpty ? segments.first : 'search';
          context.push('/$first/user/$pubkey');
        },
        onHashtagTap: (hashtag) => context.push('/search?q=$hashtag'),
      ),
    );

    if (!shouldCollapse) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
        child: bioContent,
      );
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: maxHeight),
            child: ShaderMask(
              shaderCallback: (Rect bounds) {
                return const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Colors.white, Colors.white, Colors.transparent],
                  stops: [0.0, 0.7, 1.0],
                ).createShader(bounds);
              },
              blendMode: BlendMode.dstIn,
              child: SingleChildScrollView(
                physics: const NeverScrollableScrollPhysics(),
                child: bioContent,
              ),
            ),
          ),
          const SizedBox(height: 8),
          Center(
            child: FilledButton(
              style: FilledButton.styleFrom(
                backgroundColor:
                    Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.08),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              onPressed: () => expanded.value = true,
              child: Text(
                'Read more',
                style: Theme.of(context).textTheme.labelSmall,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
