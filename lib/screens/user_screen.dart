import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import '../theme.dart';
import '../widgets/common/note_parser.dart';
import '../widgets/common/profile_identity_row.dart';
import '../widgets/app_card.dart';
import '../widgets/zap_widgets.dart';

/// User profile screen - shows any user/developer profile
class UserScreen extends HookConsumerWidget {
  const UserScreen({super.key, required this.pubkey});

  final String pubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Load user profile
    final profileState = ref.watch(
      query<Profile>(
        authors: {pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
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
          app.latestRelease.query(
            and: (release) => {
              release.latestMetadata.query(),
              release.latestAsset.query(),
            },
          ),
        },
        source: const LocalAndRemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-user-apps',
      ),
    );

    // For Zapstore pubkey, only show Zapstore's own apps (not relay-signed ones)
    final apps = pubkey == kZapstorePubkey
        ? userAppsState.models.where((a) => a.isZapstoreApp).toList()
        : userAppsState.models;

    // Query user's app stacks
    final appStacksState = ref.watch(
      query<AppStack>(
        authors: {pubkey},
        limit: 20,
        and: (pack) => {
          pack.apps.query(
            source: const LocalAndRemoteSource(
              relays: 'AppCatalog',
              stream: false,
            ),
            subscriptionPrefix: 'app-user-screen-stack-apps',
          ),
        },
        source: LocalAndRemoteSource(stream: false, relays: 'social'),
        subscriptionPrefix: 'user-screen-stacks',
        schemaFilter: appStackEventFilter,
      ),
    );
    final stacks = appStacksState.models.toList()
      ..sort((a, b) => b.event.createdAt.compareTo(a.event.createdAt));

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          // Header with avatar and name
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.only(top: 16),
              child: _UserHeader(
                profile: profile,
                pubkey: pubkey,
                isLoading: profileState is StorageLoading && profile == null,
              ),
            ),
          ),

          // Zaps widget
          SliverToBoxAdapter(child: _UserZapsList(apps: apps)),

          // Bio section with max height
          if (profile?.about != null && profile!.about!.isNotEmpty)
            SliverToBoxAdapter(child: _UserBio(profile: profile)),

          // Apps section - only show if apps exist
          if (apps.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
                child: Text(
                  'Published Apps',
                  style: context.textTheme.titleLarge,
                ),
              ),
            ),
            SliverList(
              delegate: SliverChildBuilderDelegate((context, index) {
                final app = apps[index];
                return AppCard(app: app, showSignedBy: false);
              }, childCount: apps.length),
            ),
          ],

          // App stacks section - only show if stacks exist
          if (stacks.isNotEmpty) ...[
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 32, 16, 8),
                child: Text(
                  'App Stacks',
                  style: context.textTheme.headlineMedium,
                ),
              ),
            ),
            SliverList(
              delegate: SliverChildBuilderDelegate((context, index) {
                final stack = stacks[index];
                return _StackLinkCard(stack: stack, pubkey: pubkey);
              }, childCount: stacks.length),
            ),
            // Republish stacks button - only for signed-in user viewing their own profile
            SliverToBoxAdapter(
              child: _RepublishStacksButton(stacks: stacks, pubkey: pubkey),
            ),
          ],

          // Bottom padding
          const SliverToBoxAdapter(child: SizedBox(height: 32)),
        ],
      ),
    );
  }
}

class _UserHeader extends StatelessWidget {
  const _UserHeader({
    required this.profile,
    required this.pubkey,
    this.isLoading = false,
  });

  final Profile? profile;
  final String pubkey;
  final bool isLoading;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: ProfileIdentityRow(
        pubkey: pubkey,
        profile: profile,
        isLoading: isLoading,
        avatarRadius: 40,
      ),
    );
  }
}

class _UserZapsList extends HookConsumerWidget {
  const _UserZapsList({required this.apps});

  final List<App> apps;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Don't query zaps if user has no apps
    if (apps.isEmpty) {
      return const SizedBox.shrink();
    }

    // Collect addressable tags for apps and metadata IDs
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

    // Query zaps on apps (via #a tag)
    final appZapsState = ref.watch(
      query<Zap>(
        tags: allAppTags,
        source: const LocalAndRemoteSource(relays: 'social'),
        subscriptionPrefix: 'user-screen-app-zaps',
      ),
    );

    // Query zaps on metadata (via #e tag) - for legacy compatibility
    final metadataZapsState = metadataIds.isNotEmpty
        ? ref.watch(
            query<Zap>(
              tags: {'#e': metadataIds},
              source: const LocalAndRemoteSource(relays: 'social'),
              subscriptionPrefix: 'user-screen-metadata-zaps',
            ),
          )
        : null;

    // Combine zaps from both queries
    final allZaps = {
      ...appZapsState.models,
      if (metadataZapsState != null) ...metadataZapsState.models,
    };

    if (allZaps.isEmpty) {
      return const SizedBox.shrink();
    }

    // Collect zapper pubkeys from metadata (already extracted from description tag)
    final zapperPubkeys = <String>{};
    for (final zap in allZaps) {
      // The zapper's pubkey is in event.metadata['author'], extracted from description
      final zapperPubkey = zap.event.metadata['author'] as String?;
      if (zapperPubkey != null) {
        zapperPubkeys.add(zapperPubkey);
      }
    }

    // Query profiles separately with cachedFor
    final profilesState = ref.watch(
      query<Profile>(
        authors: zapperPubkeys,
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    final profilesMap = {for (final p in profilesState.models) p.pubkey: p};

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: ZappersHorizontalList(
        zaps: allZaps.toList(),
        profilesMap: profilesMap,
      ),
    );
  }
}

class _StackLinkCard extends StatelessWidget {
  const _StackLinkCard({required this.stack, required this.pubkey});

  final AppStack stack;
  final String pubkey;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: InkWell(
        onTap: () {
          final segments = GoRouterState.of(context).uri.pathSegments;
          final first = segments.isNotEmpty ? segments.first : 'search';
          // Build naddr for the stack
          final naddr = Utils.encodeShareableIdentifier(
            AddressInput(
              identifier: stack.identifier,
              author: pubkey,
              kind: stack.event.kind,
              relays: const [],
            ),
          );
          context.push('/$first/stack/$naddr');
        },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: Theme.of(
                context,
              ).colorScheme.outline.withValues(alpha: 0.2),
            ),
          ),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      stack.name ?? stack.identifier,
                      style: context.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Icon(
                Icons.chevron_right,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
      ),
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
                backgroundColor: Theme.of(
                  context,
                ).colorScheme.onSurface.withValues(alpha: 0.08),
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
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

class _RepublishStacksButton extends HookConsumerWidget {
  const _RepublishStacksButton({
    required this.stacks,
    required this.pubkey,
  });

  final List<AppStack> stacks;
  final String pubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);

    // Only show if user is viewing their own profile
    if (signedInPubkey == null || signedInPubkey != pubkey) {
      return const SizedBox.shrink();
    }

    final isLoading = useState(false);
    final progressCount = useState(0);

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: FilledButton.icon(
        onPressed: isLoading.value
            ? null
            : () async {
                isLoading.value = true;
                progressCount.value = 0;
                try {
                  final signer = ref.read(Signer.activeSignerProvider);
                  if (signer == null) {
                    if (context.mounted) {
                      context.showError('Sign in required');
                    }
                    return;
                  }

                  final platform =
                      ref.read(packageManagerProvider.notifier).platform;

                  for (final stack in stacks) {
                    // Get existing app IDs from the stack
                    final appIds = stack.event.getTagSetValues('a').toList();

                    // Check if it's an encrypted stack (has content but no 'a' tags)
                    final isEncrypted =
                        stack.content.isNotEmpty && appIds.isEmpty;

                    if (isEncrypted) {
                      // For encrypted stacks, decrypt, then re-encrypt with platform
                      try {
                        final decryptedContent = await signer.nip44Decrypt(
                          stack.content,
                          signedInPubkey,
                        );
                        final existingAppIds =
                            (jsonDecode(decryptedContent) as List)
                                .cast<String>();

                        final partialStack = PartialAppStack.withEncryptedApps(
                          name: stack.name ?? stack.identifier,
                          identifier: stack.identifier,
                          apps: existingAppIds,
                          platform: platform,
                        );

                        final signedStack =
                            await partialStack.signWith(signer);
                        await ref.storage.save({signedStack});
                        ref.storage.publish({
                          signedStack,
                        }, source: RemoteSource(relays: 'social'));
                      } catch (e) {
                        // Skip stacks that can't be decrypted
                        continue;
                      }
                    } else {
                      // For public stacks, rebuild with platform
                      final partialStack = PartialAppStack(
                        name: stack.name ?? stack.identifier,
                        identifier: stack.identifier,
                        platform: platform,
                      );

                      for (final appId in appIds) {
                        partialStack.addApp(appId);
                      }

                      final signedStack = await partialStack.signWith(signer);
                      await ref.storage.save({signedStack});
                      ref.storage.publish({
                        signedStack,
                      }, source: RemoteSource(relays: 'social'));
                    }

                    progressCount.value++;
                  }

                  if (context.mounted) {
                    context.showInfo(
                      'Republished ${progressCount.value} stacks with platform tag',
                    );
                  }
                } catch (e) {
                  if (context.mounted) {
                    context.showError(
                      'Failed to republish stacks',
                      description: '$e',
                    );
                  }
                } finally {
                  isLoading.value = false;
                }
              },
        icon: isLoading.value
            ? const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.publish, size: 18),
        label: Text(
          isLoading.value
              ? 'Republishing ${progressCount.value}/${stacks.length}...'
              : 'Republish stacks (${stacks.length})',
        ),
      ),
    );
  }
}
