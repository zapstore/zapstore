import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';

import '../widgets/app_stack_container.dart';
import '../widgets/latest_releases_container.dart';
import '../widgets/app_card.dart';
import '../utils/extensions.dart';
import '../main.dart';
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

    // Check if storage is initialized
    final initState = ref.watch(appInitializationProvider);

    // Get platform from package manager
    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Function to perform search (only with 3+ characters)
    final performSearch = useCallback((String query) {
      final trimmed = query.trim();
      // Keep keyboard open if less than 3 characters
      if (trimmed.length < 3) {
        // Re-request focus to keep keyboard open
        searchFocusNode.requestFocus();
        return;
      }
      searchQuery.value = trimmed;
    }, [searchFocusNode]);

    final trimmedQuery = searchQuery.value.trim();

    // Authors are now loaded via profileProvider in individual AppCards with caching

    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Column(
        children: [
          // Professional search bar with better spacing
          Container(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 18),
            child: ValueListenableBuilder<TextEditingValue>(
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
                          color: Theme.of(
                            context,
                          ).colorScheme.onSurfaceVariant.withValues(alpha: 0.6),
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
                      showSkeleton: !(initState.hasValue || initState.hasError),
                    ),
                  ),

                  // Latest Releases Container
                  LatestReleasesContainer(
                    scrollController: scrollController,
                    showSkeleton: !(initState.hasValue || initState.hasError),
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
        limit: 20,
        tags: {
          '#f': {platform},
        },
        and: (app) => {
          app.latestRelease.query(
            and: (release) => {
              release.latestMetadata.query(),
              release.latestAsset.query(),
            },
          ),
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
            children: List.generate(2, (_) => const AppCard(isLoading: true)),
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
            children: [
              // App Cards - authors loaded via profileProvider in AppCard
              ...results.map(
                (app) => AppCard(app: app, showUpdateArrow: app.hasUpdate),
              ),
            ],
          ),
        const SizedBox(height: 24),
      ],
    );
  }
}
