import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/screens/main_scaffold.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/screens/developer_screen.dart';
import 'package:zapstore/screens/search_screen.dart';
import 'package:zapstore/screens/updates_screen.dart';
import 'package:zapstore/screens/profile_screen.dart';
import 'package:zapstore/screens/app_from_naddr_screen.dart';

/// Fallback screen shown when app data is not available
class _AppNotFoundScreen extends StatelessWidget {
  const _AppNotFoundScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('App Not Found')),
      body: const Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.error_outline, size: 64),
            SizedBox(height: 16),
            Text('App data not available'),
            SizedBox(height: 8),
            Text('Please try navigating from the app list'),
          ],
        ),
      ),
    );
  }
}

/// Helper to build app detail route with fallback
GoRoute _appDetailRoute() {
  return GoRoute(
    path: 'app/:id',
    builder: (context, state) {
      final app = state.extra as App?;
      if (app != null) {
        return AppDetailScreen(app: app);
      }
      return const _AppNotFoundScreen();
    },
  );
}

/// Helper to build developer route
GoRoute _developerRoute() {
  return GoRoute(
    path: 'developer/:pubkey',
    builder: (context, state) {
      final pubkey = state.pathParameters['pubkey']!;
      return DeveloperScreen(pubkey: pubkey);
    },
  );
}

final routerProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/search',
    routes: [
      // Top-level deep link route: /apps/:naddr
      // Redirect to profile branch to show with nav bar
      GoRoute(
        path: '/apps/:naddr',
        redirect: (context, state) {
          final naddr = state.pathParameters['naddr'];
          if (naddr == null || naddr.isEmpty) {
            return '/search';
          }
          return '/profile/apps/$naddr';
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
                routes: [
                  _appDetailRoute(),
                  _developerRoute(),
                ],
              ),
            ],
          ),
          // Updates tab branch with nested detail routes
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: '/updates',
                builder: (context, state) => const UpdatesScreen(),
                routes: [
                  _appDetailRoute(),
                  _developerRoute(),
                ],
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
                  GoRoute(
                    path: 'apps/:naddr',
                    builder: (context, state) {
                      final naddr = state.pathParameters['naddr']!;
                      return AppFromNaddrScreen(naddr: naddr);
                    },
                  ),
                  _developerRoute(),
                ],
              ),
            ],
          ),
        ],
      ),
    ],
  );
});
