import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/screens/diagnostics_screen.dart';
import 'package:zapstore/screens/main_scaffold.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/screens/app_stacks_screen.dart';
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

CustomTransitionPage<void> _noTransitionPage({
  required GoRouterState state,
  required Widget child,
}) {
  return CustomTransitionPage<void>(
    key: state.pageKey,
    child: child,
    transitionsBuilder: (context, animation, secondaryAnimation, child) =>
        child,
    transitionDuration: Duration.zero,
    reverseTransitionDuration: Duration.zero,
  );
}

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
    pageBuilder: (context, state) {
      final rawId = state.pathParameters['id']!;
      final resolved = _resolveNaddrRouteId(rawId);
      return _noTransitionPage(
        state: state,
        child: AppDetailScreen(
          appId: resolved.identifier,
          authorPubkey: resolved.author,
        ),
      );
    },
  );
}

/// Helper to build stack detail route
GoRoute _stackDetailRoute() {
  return GoRoute(
    path: 'stack/:id',
    pageBuilder: (context, state) {
      final rawId = state.pathParameters['id']!;
      final resolved = _resolveNaddrRouteId(rawId);
      return _noTransitionPage(
        state: state,
        child: AppStackScreen(
          stackId: resolved.identifier,
          authorPubkey: resolved.author,
        ),
      );
    },
  );
}

/// Helper to build all-stacks route
GoRoute _allStacksRoute() {
  return GoRoute(
    path: 'stacks',
    pageBuilder: (context, state) {
      return _noTransitionPage(state: state, child: const AppStacksScreen());
    },
  );
}

/// Helper to build user route
GoRoute _userRoute() {
  return GoRoute(
    path: 'user/:pubkey',
    pageBuilder: (context, state) {
      final pubkey = state.pathParameters['pubkey']!;
      return _noTransitionPage(
        state: state,
        child: UserScreen(pubkey: pubkey),
      );
    },
  );
}

final routerProvider = Provider<GoRouter>((ref) {
  String? previousPath;

  final router = GoRouter(
    navigatorKey: rootNavigatorKey,
    initialLocation: '/search',
    onException: (context, state, router) {
      router.go('/search');
    },
    routes: [
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
                pageBuilder: (context, state) => _noTransitionPage(
                  state: state,
                  child: const SearchScreen(),
                ),
                routes: [
                  _appDetailRoute(),
                  _stackDetailRoute(),
                  _allStacksRoute(),
                  _userRoute(),
                ],
              ),
            ],
          ),
          // Updates tab branch with nested detail routes
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/updates',
                pageBuilder: (context, state) => _noTransitionPage(
                  state: state,
                  child: const UpdatesScreen(),
                ),
                routes: [
                  _appDetailRoute(),
                  _stackDetailRoute(),
                  _allStacksRoute(),
                  _userRoute(),
                ],
              ),
            ],
          ),
          // Profile tab branch with nested detail routes
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/profile',
                pageBuilder: (context, state) => _noTransitionPage(
                  state: state,
                  child: const ProfileScreen(),
                ),
                routes: [
                  _appDetailRoute(),
                  _stackDetailRoute(),
                  _allStacksRoute(),
                  _userRoute(),
                  GoRoute(
                    path: 'diagnostics',
                    pageBuilder: (context, state) => _noTransitionPage(
                      state: state,
                      child: const DiagnosticsScreen(),
                    ),
                  ),
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
    previousPath = currentPath;

    Future.microtask(() {
      // Sync installed packages on every navigation to catch sideloads,
      // external installs/uninstalls, and self-updating apps.
      // This is a local-only platform channel call (~100-500ms, no network).
      unawaited(
        ref.read(packageManagerProvider.notifier).syncInstalledPackages(),
      );

      // Re-derive catalog from local DB when arriving at updates tab so
      // data written by other screens or the background service is visible
      // without waiting for the next poll cycle.
      if (isUpdatesRoute && !wasUpdatesRoute) {
        unawaited(
          ref.read(updatePollerProvider.notifier).refreshFromLocal(),
        );
      }

      // Clear completed operations when navigating away from updates
      if (wasUpdatesRoute && !isUpdatesRoute) {
        ref.read(packageManagerProvider.notifier).clearCompletedOperations();
      }
    });
  }

  router.routerDelegate.addListener(onRouteChange);
  ref.onDispose(() => router.routerDelegate.removeListener(onRouteChange));

  return router;
});
