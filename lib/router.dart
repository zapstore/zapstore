import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/screens/main_scaffold.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/screens/app_stack_screen.dart';
import 'package:zapstore/screens/user_screen.dart';
import 'package:zapstore/screens/search_screen.dart';
import 'package:zapstore/screens/updates_screen.dart';
import 'package:zapstore/screens/profile_screen.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/updates_service.dart';

/// Root paths for each navigation branch (used for back navigation handling)
const kBranchRoots = ['/search', '/updates', '/profile'];

final rootNavigatorKey = GlobalKey<NavigatorState>();

typedef _ResolvedRoute = ({String identifier, String? author});

_ResolvedRoute _resolveNaddrRouteId(String rawId) {
  if (rawId.startsWith('naddr1')) {
    try {
      final decoded = Utils.decodeShareableIdentifier(rawId);
      if (decoded is AddressData) {
        return (identifier: decoded.identifier, author: decoded.author);
      }
    } catch (_) {
      // Fall back to treating it as a plain identifier.
    }
  }
  return (identifier: rawId, author: null);
}

/// Helper to build app detail route
GoRoute _appDetailRoute() {
  return GoRoute(
    path: 'app/:id',
    builder: (context, state) {
      final rawId = state.pathParameters['id']!;
      final resolved = _resolveNaddrRouteId(rawId);
      return AppDetailScreen(
        appId: resolved.identifier,
        authorPubkey: resolved.author,
      );
    },
  );
}

/// Helper to build stack detail route
GoRoute _stackDetailRoute() {
  return GoRoute(
    path: 'stack/:id',
    builder: (context, state) {
      final rawId = state.pathParameters['id']!;
      final resolved = _resolveNaddrRouteId(rawId);
      return AppStackScreen(
        stackId: resolved.identifier,
        authorPubkey: resolved.author,
      );
    },
  );
}

/// Helper to build user route
GoRoute _userRoute() {
  return GoRoute(
    path: 'user/:pubkey',
    builder: (context, state) {
      final pubkey = state.pathParameters['pubkey']!;
      return UserScreen(pubkey: pubkey);
    },
  );
}

final routerProvider = Provider<GoRouter>((ref) {
  String? previousPath;

  final router = GoRouter(
    navigatorKey: rootNavigatorKey,
    initialLocation: '/search',
    routes: [
      // Top-level route for market:// intents
      // Redirect to search branch to show with nav bar
      GoRoute(
        path: '/market/:packageId',
        redirect: (context, state) {
          final packageId = state.pathParameters['packageId'];
          if (packageId == null || packageId.isEmpty) {
            return '/search';
          }
          return '/search/app/$packageId';
        },
      ),
      // Single stateful shell route that handles everything
      StatefulShellRoute.indexedStack(
        builder: (context, state, navigationShell) =>
            MainScaffold(navigationShell: navigationShell),
        branches: [
          // Search tab branch with nested detail routes
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/search',
                builder: (context, state) => const SearchScreen(),
                routes: [_appDetailRoute(), _stackDetailRoute(), _userRoute()],
              ),
            ],
          ),
          // Updates tab branch with nested detail routes
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/updates',
                builder: (context, state) => const UpdatesScreen(),
                routes: [_appDetailRoute(), _stackDetailRoute(), _userRoute()],
              ),
            ],
          ),
          // Profile tab branch with nested detail routes
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/profile',
                builder: (context, state) => const ProfileScreen(),
                routes: [
                  _appDetailRoute(),
                  _stackDetailRoute(),
                  _userRoute(),
                ],
              ),
            ],
          ),
        ],
      ),
    ],
  );

  // Listen for route changes to trigger actions
  void onRouteChange() {
    final currentPath = router.routerDelegate.currentConfiguration.uri.path;
    final isUpdatesRoute = currentPath.startsWith('/updates');
    final wasUpdatesRoute = previousPath?.startsWith('/updates') ?? false;

    // Sync installed packages and refresh app data when navigating TO the updates branch
    if (isUpdatesRoute && !wasUpdatesRoute) {
      unawaited(
        ref.read(packageManagerProvider.notifier).syncInstalledPackages().then((
          _,
        ) {
          // Trigger a recalculation of apps after sync
          // (introduced after seeing cases of stale versions)
          ref.invalidate(categorizedUpdatesProvider);
        }),
      );
    }

    // Clear completed operations when navigating AWAY from updates
    // This cleans up the "All done" state without affecting the count while visible
    if (wasUpdatesRoute && !isUpdatesRoute) {
      ref.read(packageManagerProvider.notifier).clearCompletedOperations();
    }

    previousPath = currentPath;
  }

  router.routerDelegate.addListener(onRouteChange);
  ref.onDispose(() => router.routerDelegate.removeListener(onRouteChange));

  return router;
});
