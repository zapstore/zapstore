import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';

/// Cache duration before fetching from remote relays
const kProfileCacheDuration = Duration(hours: 2);

/// Debounce duration for batching profile requests
const kProfileFetchDebounce = Duration(milliseconds: 500);

/// Tracks last fetch times for profiles by pubkey
final _profileFetchTimesProvider = StateProvider<Map<String, DateTime>>(
  (ref) => {},
);

/// Tracks profile update timestamps to trigger reactivity
final _profileUpdateTriggerProvider = StateProvider<Map<String, DateTime>>(
  (ref) => {},
);

/// Service for managing profile fetches with cache awareness and debounced batching
class ProfileService {
  ProfileService(this.ref);

  final Ref ref;

  /// Pending pubkeys waiting to be fetched (collected during debounce window)
  final Set<String> _pendingPubkeys = {};

  /// Pubkeys currently being fetched (prevents duplicate in-flight requests)
  final Set<String> _inFlightPubkeys = {};

  /// Debounce timer for batching profile fetches
  Timer? _debounceTimer;

  /// Request a profile fetch with debouncing.
  /// Requests are collected and batched after the debounce window.
  void requestProfile(String pubkey) {
    // Skip if already pending or in-flight
    if (_pendingPubkeys.contains(pubkey) || _inFlightPubkeys.contains(pubkey)) {
      return;
    }

    _pendingPubkeys.add(pubkey);

    // Reset/start debounce timer
    _debounceTimer?.cancel();
    _debounceTimer = Timer(kProfileFetchDebounce, _executeBatchFetch);
  }

  /// Executes batch fetch for all pending profiles
  Future<void> _executeBatchFetch() async {
    if (_pendingPubkeys.isEmpty) return;

    // Move pending to in-flight
    final pubkeysToFetch = Set<String>.from(_pendingPubkeys);
    _pendingPubkeys.clear();
    _inFlightPubkeys.addAll(pubkeysToFetch);

    try {
      await _fetchProfilesBatch(pubkeysToFetch);
    } finally {
      // Remove from in-flight when done
      _inFlightPubkeys.removeAll(pubkeysToFetch);
    }
  }

  /// Internal batch fetch implementation
  Future<void> _fetchProfilesBatch(Set<String> pubkeys) async {
    if (pubkeys.isEmpty) return;

    final storage = ref.read(storageNotifierProvider.notifier);
    final now = DateTime.now();

    debugPrint('ProfileService: Batch fetching ${pubkeys.length} profiles');

    try {
      // Use RemoteSource with background: false to ensure we wait for relay data.
      // This is fast because we're batching - one request for all profiles.
      final profiles = await storage.query(
        RequestFilter<Profile>(authors: pubkeys).toRequest(),
        source: const RemoteSource(
          relays: {'social', 'vertex'},
          stream: false,
          background: false, // Wait for EOSE - fast since batched
        ),
      );

      debugPrint('ProfileService: Fetched ${profiles.length} profiles');

      // Save to local storage so they're available for LocalSource queries
      if (profiles.isNotEmpty) {
        await storage.save(profiles.toSet());

        // Trigger reactivity by updating timestamp for each profile
        ref.read(_profileUpdateTriggerProvider.notifier).update((state) {
          final updated = {...state};
          for (final profile in profiles) {
            updated[profile.event.pubkey] = DateTime.now();
          }
          return updated;
        });
      }

      // Update fetch times for all requested pubkeys (even if not found)
      ref.read(_profileFetchTimesProvider.notifier).update((state) {
        final updated = {...state};
        for (final pubkey in pubkeys) {
          updated[pubkey] = now;
        }
        return updated;
      });
    } catch (error, stackTrace) {
      debugPrint(
        'ProfileService: Failed to batch fetch profiles: $error\n$stackTrace',
      );
    }
  }

  /// Fetches profiles from remote, respecting cache duration.
  /// Pass a Set of pubkeys - use Set of 1 for single profile.
  /// This method bypasses debouncing for immediate batch fetches.
  Future<void> fetchProfiles(Set<String> pubkeys) async {
    if (pubkeys.isEmpty) return;

    final fetchTimes = ref.read(_profileFetchTimesProvider);
    final now = DateTime.now();

    // Filter out recently fetched profiles and in-flight requests
    final staleProfiles = pubkeys.where((pubkey) {
      if (_inFlightPubkeys.contains(pubkey)) return false;
      final lastFetch = fetchTimes[pubkey];
      return lastFetch == null ||
          now.difference(lastFetch) > kProfileCacheDuration;
    }).toSet();

    if (staleProfiles.isEmpty) return;

    // Mark as in-flight
    _inFlightPubkeys.addAll(staleProfiles);

    try {
      await _fetchProfilesBatch(staleProfiles);
    } finally {
      _inFlightPubkeys.removeAll(staleProfiles);
    }
  }

  /// Invalidate cache for a specific profile, forcing a remote fetch on next access.
  void invalidateProfile(String pubkey) {
    ref.read(_profileFetchTimesProvider.notifier).update((state) {
      final updated = {...state};
      updated.remove(pubkey);
      return updated;
    });
  }

  /// Invalidate cache for all profiles, forcing remote fetches on next access.
  /// Useful for pull-to-refresh scenarios.
  void invalidateAllProfiles() {
    ref.read(_profileFetchTimesProvider.notifier).state = {};
  }
}

/// Provider for the profile service
final profileServiceProvider = Provider((ref) => ProfileService(ref));

/// Provider family for accessing profiles with reactive updates
///
/// - Returns local data immediately if available
/// - Reacts to profile updates from batch fetches via trigger mechanism
/// - Streams updates when profile data changes
final profileProvider =
    AutoDisposeProvider.family<AsyncValue<Profile?>, String>((ref, pubkey) {
      // Watch update trigger to react when profiles are saved by fetchProfiles()
      ref.watch(_profileUpdateTriggerProvider.select((state) => state[pubkey]));

      // Use query with LocalAndRemoteSource for reactive updates
      final profileState = ref.watch(
        query<Profile>(
          authors: {pubkey},
          source: LocalAndRemoteSource(
            relays: {'social', 'vertex'},
            stream: true,
            background: true, // Don't block on remote
          ),
        ),
      );

      return switch (profileState) {
        StorageLoading() => const AsyncValue.loading(),
        StorageError(:final exception) => AsyncValue.error(
          exception,
          StackTrace.current,
        ),
        StorageData(:final models) => AsyncValue.data(
          models.isNotEmpty ? models.first : null,
        ),
      };
    });
