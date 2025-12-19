import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/screens/main_scaffold.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/screens/user_screen.dart';
import 'package:zapstore/screens/search_screen.dart';
import 'package:zapstore/screens/updates_screen.dart';
import 'package:zapstore/screens/profile_screen.dart';

typedef _ResolvedAppRoute = ({String identifier, String? author});

_ResolvedAppRoute _resolveAppRouteId(String rawId) {
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
      final resolved = _resolveAppRouteId(rawId);
      return AppDetailScreen(
        appId: resolved.identifier,
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
  return GoRouter(
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
                routes: [_appDetailRoute(), _userRoute()],
              ),
            ],
          ),
          // Updates tab branch with nested detail routes
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/updates',
                builder: (context, state) => const UpdatesScreen(),
                routes: [_appDetailRoute(), _userRoute()],
              ),
            ],
          ),
          // Profile tab branch with nested detail routes
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/profile',
                builder: (context, state) => const ProfileScreen(),
                routes: [_appDetailRoute(), _userRoute()],
              ),
            ],
          ),
        ],
      ),
    ],
  );
});
