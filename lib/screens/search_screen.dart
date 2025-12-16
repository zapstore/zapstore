import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';

import '../widgets/app_pack_container.dart';
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

    // Check app initialization state
    final initState = ref.watch(appInitializationProvider);

    // Get platform from package manager
    final platform = ref.read(packageManagerProvider.notifier).platform;

    // Use reactive query instead of manual search
    // Query system handles relay connectivity internally
    final searchResultsState = searchQuery.value.trim().isNotEmpty
        ? ref.watch(
            query<App>(
              search: searchQuery.value.trim(),
              limit: 20,
              tags: {
                '#f': {platform},
              },
              and: (app) => {
                app.latestRelease,
                app.latestRelease.value?.latestMetadata,
              },
              // Force the search to hit the default relay group (relay.zapstore.dev)
              // so a connection appears in Debug Info when searching.
              source: const RemoteSource(relays: 'AppCatalog', stream: false),
              subscriptionPrefix: 'search-results',
            ),
          )
        : null;

    // Function to perform search
    final performSearch = useCallback((String query) {
      if (query.trim().isEmpty) {
        searchQuery.value = '';
        return;
      }
      searchQuery.value = query.trim();
    }, []);

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

    // Extract search results and states
    final searchResults = searchResultsState is StorageData
        ? (searchResultsState as StorageData<App>).models
        : <App>[];
    final isSearching = searchResultsState is StorageLoading;
    final searchError = searchResultsState is StorageError
        ? (searchResultsState as StorageError<App>).exception.toString()
        : null;

    // Show search results (query system handles relay connectivity internally)
    final effectiveResults = searchResults;
    // Show loading if: query exists and either loading or query hasn't started yet
    final effectiveIsSearching =
        searchQuery.value.trim().isNotEmpty &&
        (isSearching || searchResultsState == null);

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
                  if (searchQuery.value.isNotEmpty)
                    _buildSearchResults(
                      context,
                      searchQuery.value,
                      effectiveResults,
                      effectiveIsSearching,
                      searchError,
                    ),

                  // App Curation Container with professional spacing
                  Padding(
                    padding: const EdgeInsets.only(bottom: 16),
                    child: AppPackContainer(
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

  Widget _buildSearchResults(
    BuildContext context,
    String query,
    List<App> results,
    bool isSearching,
    String? error,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (isSearching)
          Column(
            children: List.generate(3, (index) => AppCard(isLoading: true)),
          )
        else if (error != null)
          _buildSearchErrorState(context, error)
        else if (results.isEmpty)
          _buildSearchEmptyState(context, query)
        else
          _buildSearchResultsList(context, results),

        const SizedBox(height: 24),
      ],
    );
  }

  Widget _buildSearchErrorState(BuildContext context, String error) {
    return Padding(
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
    );
  }

  Widget _buildSearchEmptyState(BuildContext context, String query) {
    // Show empty state when no results found
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Center(
        child: Column(
          children: [
            Icon(Icons.search_off, size: 48, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text('No results found', style: context.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(
              'No apps found for "$query"',
              style: context.textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSearchResultsList(BuildContext context, List<App> results) {
    return Column(
      children: [
        // App Cards - authors loaded via profileProvider in AppCard
        ...results.map((app) => _SearchResultCard(app: app)),
      ],
    );
  }
}

/// Helper widget for search result cards with version information
class _SearchResultCard extends ConsumerWidget {
  const _SearchResultCard({required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Check if app has updates
    // Author loaded via profileProvider in AppCard
    return AppCard(app: app, showUpdateArrow: app.hasUpdate);
  }
}
