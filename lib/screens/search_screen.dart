import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/nostr_route.dart';

import '../widgets/app_stack_container.dart';
import '../widgets/latest_releases_container.dart';
import '../widgets/search_app_card.dart';
import '../utils/extensions.dart';
import '../main.dart';
import '../services/device_key_service.dart';
import '../services/package_manager/package_manager.dart';

/// Main search and app discovery screen
class SearchScreen extends HookConsumerWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchController = useTextEditingController();
    final scrollController = useScrollController();
    final searchFocusNode = useFocusNode();
    final searchQuery = useState<String>('');

    // Seed search from `/search?q=...` deep links. Re-runs whenever the
    // route's `q` parameter changes; in-screen edits don't change the URL,
    // so they are not clobbered by this effect.
    final routeQuery =
        GoRouterState.of(context).uri.queryParameters['q']?.trim() ?? '';
    useEffect(() {
      if (routeQuery.isNotEmpty) {
        searchController.text = routeQuery;
        searchController.selection = TextSelection.collapsed(
          offset: routeQuery.length,
        );
        searchQuery.value = routeQuery;
      }
      return null;
    }, [routeQuery]);

    // Skeleton unlocks as soon as local storage is usable. Gating on
    // `appInitializationProvider` would wait on the full init chain
    // (including network warm-ups), which violates local-first.
    final storageState = ref.watch(storageReadyProvider);

    // Get platform from package manager
    final platform = ref.read(packageManagerProvider.notifier).platform;

    final performSearch = useCallback((String query) {
      final trimmed = query.trim();
      if (navigateToContent(context, trimmed, fallbackLaunch: false)) {
        searchController.clear();
        searchQuery.value = '';
        return;
      }
      if (trimmed.length < 3) {
        searchFocusNode.requestFocus();
        return;
      }
      searchQuery.value = trimmed;
    }, [searchFocusNode]);

    final trimmedQuery = searchQuery.value.trim();

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Column(
        children: [
          // Sticky search bar (+ optional device-key reminder)
          Container(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 18),
            child: Column(
              children: [
                ValueListenableBuilder<TextEditingValue>(
                  valueListenable: searchController,
                  builder: (context, value, _) {
                    final hasText = value.text.isNotEmpty;

                    return SearchBar(
                      controller: searchController,
                      focusNode: searchFocusNode,
                      hintText: 'Search apps',
                      leading: Icon(
                        Icons.search_rounded,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                      trailing: [
                        if (hasText)
                          IconButton(
                            icon: Icon(
                              Icons.clear_rounded,
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurfaceVariant
                                  .withValues(alpha: 0.6),
                            ),
                            onPressed: () {
                              searchController.clear();
                              searchQuery.value = '';
                              searchFocusNode.requestFocus();
                            },
                            tooltip: 'Clear search',
                          ),
                      ],
                      onSubmitted: performSearch,
                      elevation: WidgetStateProperty.all(0),
                      backgroundColor: WidgetStateProperty.all(
                        Theme.of(context).colorScheme.surfaceContainerHighest
                            .withValues(alpha: 0.8),
                      ),
                      shape: WidgetStateProperty.all(
                        RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(16),
                          side: BorderSide(
                            color: Theme.of(
                              context,
                            ).colorScheme.outline.withValues(alpha: 0.3),
                            width: 1,
                          ),
                        ),
                      ),
                    );
                  },
                ),
                if (ref.watch(isNewDeviceKeyProvider)) ...[
                  const SizedBox(height: 12),
                  _NewDeviceKeyReminder(
                    onTap: () => context.go('/profile'),
                    onDismiss: () =>
                        ref.read(isNewDeviceKeyProvider.notifier).state = false,
                  ),
                ],
              ],
            ),
          ),
          // Scrollable content
          Expanded(
            child: SingleChildScrollView(
              controller: scrollController,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Search Results Section
                  if (trimmedQuery.isNotEmpty)
                    _SearchResultsSection(
                      searchQuery: trimmedQuery,
                      platform: platform,
                      scrollController: scrollController,
                    ),

                  // App Stacks Container
                  Padding(
                    padding: const EdgeInsets.only(bottom: 14),
                    child: AppStackContainer(
                      showSkeleton:
                          !(storageState.hasValue || storageState.hasError),
                    ),
                  ),

                  // Latest Releases Container
                  LatestReleasesContainer(
                    showSkeleton:
                        !(storageState.hasValue || storageState.hasError),
                    scrollController: scrollController,
                  ),

                  const SizedBox(height: 16), // Bottom padding
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _NewDeviceKeyReminder extends StatelessWidget {
  const _NewDeviceKeyReminder({required this.onTap, required this.onDismiss});

  final VoidCallback onTap;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    // Elevated dark grey chip — quiet welcome, readable on the blue-black shell.
    const toastBackground = Color(0xFF2C313A);
    const toastBorder = Color(0xFF3E4552);
    const toastForeground = Color(0xFFF2F4F7);
    const toastMuted = Color(0xFFC5CAD3);
    const iconWell = Color(0xFF3A404C);

    return Material(
      color: toastBackground,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: const BorderSide(color: toastBorder),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: Colors.white10,
        highlightColor: Colors.white10,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 4, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: iconWell,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: const Icon(
                  Icons.key_rounded,
                  color: toastForeground,
                  size: 20,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Welcome to Zapstore',
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        color: toastForeground,
                        fontWeight: FontWeight.w700,
                        letterSpacing: -0.2,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Used Zapstore before? Restore your device key via nsec or by signing in with Amber.',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: toastMuted,
                        height: 1.35,
                      ),
                    ),
                  ],
                ),
              ),
              IconButton(
                onPressed: onDismiss,
                tooltip: 'Dismiss',
                icon: Icon(
                  Icons.close_rounded,
                  color: toastMuted.withValues(alpha: 0.9),
                ),
                constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
                padding: EdgeInsets.zero,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SearchResultsSection extends HookConsumerWidget {
  const _SearchResultsSection({
    required this.searchQuery,
    required this.platform,
    required this.scrollController,
  });

  final String searchQuery;
  final String platform;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final searchResultsState = ref.watch(
      query<App>(
        search: searchQuery,
        limit: 10,
        tags: {
          '#f': {platform},
        },
        // Force the search to hit the default relay group (relay.zapstore.dev)
        // so a connection appears in Debug Info when searching.
        source: const RemoteSource(relays: 'AppCatalog', stream: false),
        subscriptionPrefix: 'app-search-results',
      ),
    );

    // Auto-scroll to top when search results change from loading to data
    useEffect(() {
      if (searchResultsState is StorageData && scrollController.hasClients) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          scrollController.animateTo(
            0,
            duration: const Duration(milliseconds: 100),
            curve: Curves.easeInOut,
          );
        });
      }
      return null;
    }, [searchResultsState]);

    final results = searchResultsState is StorageData
        ? (searchResultsState as StorageData<App>).models
        : <App>[];
    final isSearching = searchResultsState is StorageLoading;
    final error = searchResultsState is StorageError
        ? (searchResultsState as StorageError<App>).exception.toString()
        : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (isSearching)
          Column(
            children: List.generate(
              2,
              (_) => const SearchAppCard(isLoading: true),
            ),
          )
        else if (error != null)
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: Center(
              child: Column(
                children: [
                  Icon(Icons.error_outline, size: 48, color: Colors.red[400]),
                  const SizedBox(height: 16),
                  Text('Search Error', style: context.textTheme.titleMedium),
                  const SizedBox(height: 8),
                  Text(
                    error,
                    style: context.textTheme.bodySmall,
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          )
        else if (results.isEmpty)
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: Center(
              child: Column(
                children: [
                  Icon(Icons.search_off, size: 48, color: Colors.grey[400]),
                  const SizedBox(height: 16),
                  Text(
                    'No results found',
                    style: context.textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'No apps found for "$searchQuery"',
                    style: context.textTheme.bodySmall,
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          )
        else
          Column(
            children: results.map((app) => SearchAppCard(app: app)).toList(),
          ),
        const SizedBox(height: 24),
      ],
    );
  }
}
