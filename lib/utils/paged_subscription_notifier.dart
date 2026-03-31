import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';

/// State for a chronologically-ordered, paged list backed by a live subscription.
///
/// [firstPage] is the streaming first-page result — local cache is shown
/// immediately and updated as remote events arrive in the background.
/// [olderItems] accumulates items loaded via scroll-triggered [loadMore].
class PagedState<T extends Model<dynamic>> {
  final StorageState<T> firstPage;
  final List<T> olderItems;
  final bool isLoadingMore;
  final bool hasMore;

  const PagedState({
    required this.firstPage,
    required this.olderItems,
    required this.isLoadingMore,
    required this.hasMore,
  });

  factory PagedState.initial() => PagedState(
    firstPage: StorageLoading<T>(const []),
    olderItems: const [],
    isLoadingMore: false,
    hasMore: true,
  );

  /// All currently loaded items: live first page + accumulated older pages.
  List<T> get combined => [...firstPage.models, ...olderItems];

  PagedState<T> copyWith({
    StorageState<T>? firstPage,
    List<T>? olderItems,
    bool? isLoadingMore,
    bool? hasMore,
  }) => PagedState(
    firstPage: firstPage ?? this.firstPage,
    olderItems: olderItems ?? this.olderItems,
    isLoadingMore: isLoadingMore ?? this.isLoadingMore,
    hasMore: hasMore ?? this.hasMore,
  );
}

/// Base notifier for a descending-date list with live subscription + infinite scroll.
///
/// ## Pattern
/// - **First page**: `stream: true` — serves local cache immediately, then merges
///   new relay events as they arrive in the background (local-first).
/// - **Older pages**: one-shot fetches (`stream: false`) with an `until` cursor
///   derived from the oldest loaded item. Never re-subscribed.
///
/// ## Subclass contract
/// 1. Override [startSubscription] — call `ref.listen` with `stream: true` and
///    forward each update to [updateFirstPage].
/// 2. Override [fetchOlderPage] — imperatively fetch one page before [until]
///    with `stream: false`. Return items and raw event count.
/// 3. Override [getId] and [getCreatedAt] for deduplication and cursor.
abstract class PagedSubscriptionNotifier<T extends Model<dynamic>>
    extends StateNotifier<PagedState<T>> {
  PagedSubscriptionNotifier(this.ref) : super(PagedState.initial()) {
    startSubscription();
  }

  final Ref ref;

  /// Items per page. Must match the `limit` used in both queries.
  int get pageSize => 5;

  /// Set up the first-page subscription with `stream: true`.
  /// Must call [updateFirstPage] on every state update.
  void startSubscription();

  /// Fetch one page of items with creation time strictly before [until].
  /// Use `stream: false`. Returns items and raw event count for has-more detection.
  Future<({List<T> items, int count})> fetchOlderPage(DateTime until);

  /// Stable unique ID for deduplication across pages.
  String getId(T item);

  /// Creation timestamp used as the pagination cursor.
  DateTime getCreatedAt(T item);

  /// Called by [startSubscription] with each incoming first-page state.
  /// Merges live data and removes stale duplicates from older pages.
  @protected
  void updateFirstPage(StorageState<T> next) {
    if (next is StorageData<T>) {
      final liveIds = next.models.map(getId).toSet();
      final filteredOlder = state.olderItems
          .where((item) => !liveIds.contains(getId(item)))
          .toList();
      state = state.copyWith(firstPage: next, olderItems: filteredOlder);
    } else {
      state = state.copyWith(firstPage: next);
    }
    if (next is StorageError<T>) {
      state = state.copyWith(isLoadingMore: false);
    }
  }

  /// Load the next page of older items. Safe to call from scroll listeners.
  Future<void> loadMore() async {
    final combined = state.combined;
    if (state.isLoadingMore || !state.hasMore || combined.isEmpty) return;

    final oldest = combined
        .map(getCreatedAt)
        .reduce((a, b) => a.isBefore(b) ? a : b)
        .subtract(const Duration(milliseconds: 1));

    state = state.copyWith(isLoadingMore: true);

    try {
      final result = await fetchOlderPage(oldest);
      if (result.items.isNotEmpty) {
        final existingIds = combined.map(getId).toSet();
        final unique = result.items
            .where((item) => !existingIds.contains(getId(item)))
            .toList();
        state = state.copyWith(
          olderItems: [...state.olderItems, ...unique],
          isLoadingMore: false,
          hasMore: result.count >= pageSize,
        );
      } else {
        state = state.copyWith(isLoadingMore: false, hasMore: false);
      }
    } catch (e) {
      state = state.copyWith(isLoadingMore: false);
      rethrow;
    }
  }
}
