import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';

/// Cache duration before fetching from remote relays
const kProfileCacheDuration = Duration(hours: 2);

/// Tracks last fetch times for profiles by pubkey
final _profileFetchTimesProvider = StateProvider<Map<String, DateTime>>(
  (ref) => {},
);

/// Tracks profile update timestamps to trigger reactivity
final _profileUpdateTriggerProvider = StateProvider<Map<String, DateTime>>(
  (ref) => {},
);

final _profileRemoteFetchProvider = FutureProvider.autoDispose
    .family<void, String>((ref, pubkey) async {
      final storage = ref.read(storageNotifierProvider.notifier);
      final request = RequestFilter<Profile>(authors: {pubkey}).toRequest();

      try {
        final profiles = await storage.query(
          request,
          source: const RemoteSource(
            relays: 'social',
            stream: false,
            background: false,
          ),
        );

        // Save to local storage
        if (profiles.isNotEmpty) {
          await storage.save(profiles.toSet());
        }

        ref.read(_profileFetchTimesProvider.notifier).update((state) {
          return {...state, pubkey: DateTime.now()};
        });
      } catch (error, stackTrace) {
        debugPrint('Failed to fetch profile $pubkey: $error\n$stackTrace');
      }
    });

/// Service for managing profile fetches with cache awareness
class ProfileService {
  ProfileService(this.ref);
  
  final Ref ref;
  
  /// Fetches profiles from remote, respecting cache duration.
  /// Pass a Set of pubkeys - use Set of 1 for single profile.
  Future<void> fetchProfiles(Set<String> pubkeys) async {
    if (pubkeys.isEmpty) return;
    
    final fetchTimes = ref.read(_profileFetchTimesProvider);
    final now = DateTime.now();
    
    // Filter out recently fetched profiles
    final staleProfiles = pubkeys.where((pubkey) {
      final lastFetch = fetchTimes[pubkey];
      return lastFetch == null || 
             now.difference(lastFetch) > kProfileCacheDuration;
    }).toSet();
    
    if (staleProfiles.isEmpty) return;
    
    try {
      final profiles = await ref.read(storageNotifierProvider.notifier).query(
        RequestFilter<Profile>(authors: staleProfiles).toRequest(),
        source: const RemoteSource(
          relays: 'social',
          stream: false,
          background: false,
        ),
      );
      
      // Save to local storage so they're available for LocalSource queries
      if (profiles.isNotEmpty) {
        await ref.read(storageNotifierProvider.notifier).save(profiles.toSet());
        
        // Trigger reactivity by updating timestamp for each profile
        ref.read(_profileUpdateTriggerProvider.notifier).update((state) {
          final updated = {...state};
          for (final profile in profiles) {
            updated[profile.event.pubkey] = DateTime.now();
          }
          return updated;
        });
      }
      
      // Update fetch times for all profiles
      ref.read(_profileFetchTimesProvider.notifier).update((state) {
        final updated = {...state};
        for (final pubkey in staleProfiles) {
          updated[pubkey] = now;
        }
        return updated;
      });
    } catch (error, stackTrace) {
      debugPrint('Failed to fetch profiles: $error\n$stackTrace');
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

/// Provider family for accessing profiles with smart caching
///
/// Automatically determines whether to trigger a remote refresh:
/// - If profile was fetched from remote < 2 hours ago: keep cached data
/// - If profile was never fetched or > 2 hours ago: run a background remote fetch
/// - Reacts to profile updates via _profileUpdateTriggerProvider
final profileProvider =
    AutoDisposeProvider.family<AsyncValue<Profile?>, String>((ref, pubkey) {
      final lastFetchTime = ref.watch(
        _profileFetchTimesProvider.select((state) => state[pubkey]),
      );

      // Watch update trigger to react when profiles are saved
      ref.watch(
        _profileUpdateTriggerProvider.select((state) => state[pubkey]),
      );

      final now = DateTime.now();
      final shouldFetchRemote =
          lastFetchTime == null ||
          now.difference(lastFetchTime) > kProfileCacheDuration;

      // Trigger background fetch if cache is stale
      if (shouldFetchRemote) {
        ref.watch(_profileRemoteFetchProvider(pubkey));
      }

      // Read from local storage (will re-run when update trigger changes)
      final profileState = ref.watch(
        query<Profile>(authors: {pubkey}, source: const LocalSource()),
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
