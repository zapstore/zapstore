// ignore_for_file: prefer_const_literals_to_create_immutables, prefer_const_constructors

import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:ndk/ndk.dart' as ndk;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/screens/profile_screen.dart';
import 'package:zapstore/screens/search_screen.dart';

void main() {
  runApp(
    ProviderScope(
      overrides: [
        configureRepositoryLocalStorage(
            clear: LocalStorageClearStrategy.always),
      ],
      child: const ZapstoreApp(),
    ),
  );
}

class ZapstoreApp extends StatelessWidget {
  const ZapstoreApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      routerConfig: goRouter,
      debugShowCheckedModeBanner: false,
      darkTheme: ThemeData(
        primarySwatch: Colors.purple,
        brightness: Brightness.dark,
      ),
      theme: ThemeData.dark(useMaterial3: true),
    );
  }
}

// private navigators
final _rootNavigatorKey = GlobalKey<NavigatorState>();
final _searchNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'search');
final _updatesNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'updates');
final _profileNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'profile');
final _notificationsNavigatorKey =
    GlobalKey<NavigatorState>(debugLabel: 'notifications');

final goRouter = GoRouter(
  initialLocation: '/search',
  // * Passing a navigatorKey causes an issue on hot reload:
  // * https://github.com/flutter/flutter/issues/113757#issuecomment-1518421380
  // * However it's still necessary otherwise the navigator pops back to
  // * root on hot reload
  navigatorKey: _rootNavigatorKey,
  // debugLogDiagnostics: true,
  routes: [
    // Stateful navigation based on:
    // https://github.com/flutter/packages/blob/main/packages/go_router/example/lib/stateful_shell_route.dart
    StatefulShellRoute.indexedStack(
      builder: (context, state, navigationShell) {
        return ScaffoldWithNestedNavigation(navigationShell: navigationShell);
      },
      branches: [
        StatefulShellBranch(
          navigatorKey: _searchNavigatorKey,
          routes: [
            GoRoute(
              path: '/search',
              pageBuilder: (context, state) => NoTransitionPage(
                child: SearchScreen(),
              ),
              routes: [
                GoRoute(
                  path: 'details',
                  builder: (context, state) =>
                      AppDetailScreen(release: state.extra as Release),
                ),
              ],
            ),
          ],
        ),
        StatefulShellBranch(
          navigatorKey: _updatesNavigatorKey,
          routes: [
            GoRoute(
              path: '/updates',
              pageBuilder: (context, state) => NoTransitionPage(
                child: Center(
                  child: ElevatedButton(
                    onPressed: () => {},
                    child: const Text('Updates coming soon!'),
                  ),
                ),
              ),
            ),
          ],
        ),
        StatefulShellBranch(
          navigatorKey: _profileNavigatorKey,
          routes: [
            GoRoute(
              path: '/profile',
              pageBuilder: (context, state) => NoTransitionPage(
                child: Center(
                  child: ProfileScreen(),
                ),
              ),
            ),
          ],
        ),
        StatefulShellBranch(
          navigatorKey: _notificationsNavigatorKey,
          routes: [
            GoRoute(
              path: '/notifications',
              pageBuilder: (context, state) => NoTransitionPage(
                child: Center(
                  child: ElevatedButton(
                    onPressed: () => {},
                    child: const Text('Notifications coming soon!'),
                  ),
                ),
              ),
            ),
          ],
        ),
      ],
    ),
  ],
);

final newInitializer = FutureProvider<void>((ref) async {
  await ref.read(repositoryInitializerProvider.future);
  await ref.read(ndk.frameProvider.notifier).initialize('wss://relay.damus.io');
});

// Stateful navigation based on:
// https://github.com/flutter/packages/blob/main/packages/go_router/example/lib/stateful_shell_route.dart
class ScaffoldWithNestedNavigation extends HookConsumerWidget {
  const ScaffoldWithNestedNavigation({
    Key? key,
    required this.navigationShell,
  }) : super(
            key: key ?? const ValueKey<String>('ScaffoldWithNestedNavigation'));
  final StatefulNavigationShell navigationShell;

  void _goBranch(int index) {
    navigationShell.goBranch(
      index,
      // A common pattern when using bottom navigation bars is to support
      // navigating to the initial location when tapping the item that is
      // already active. This example demonstrates how to support this behavior,
      // using the initialLocation parameter of goBranch.
      initialLocation: index == navigationShell.currentIndex,
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
          if (constraints.maxWidth < 450) {
            return ScaffoldWithNavigationBar(
              body: navigationShell,
              selectedIndex: navigationShell.currentIndex,
              onDestinationSelected: _goBranch,
            );
          } else {
            return ScaffoldWithNavigationRail(
              body: navigationShell,
              selectedIndex: navigationShell.currentIndex,
              onDestinationSelected: _goBranch,
            );
          }
        },
      ),
    );
  }
}

class ScaffoldWithNavigationBar extends HookConsumerWidget {
  const ScaffoldWithNavigationBar({
    super.key,
    required this.body,
    required this.selectedIndex,
    required this.onDestinationSelected,
  });
  final Widget body;
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final initializer = ref.watch(newInitializer);
    return Scaffold(
      body: initializer.when(
        data: (_) => body,
        error: (e, _) => const Text('error'),
        loading: () => const Center(child: CircularProgressIndicator()),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: selectedIndex,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysHide,
        destinations: const [
          NavigationDestination(
            label: 'Search',
            icon: Icon(Icons.search_outlined),
          ),
          NavigationDestination(
            label: 'Updates',
            icon: Icon(Icons.download_for_offline_outlined),
          ),
          NavigationDestination(
            label: 'Profile',
            icon: Icon(Icons.person_outline),
          ),
          NavigationDestination(
            label: 'Notifications',
            icon: Icon(Icons.notifications_outlined),
          ),
        ],
        onDestinationSelected: onDestinationSelected,
      ),
    );
  }
}

class ScaffoldWithNavigationRail extends StatelessWidget {
  const ScaffoldWithNavigationRail({
    super.key,
    required this.body,
    required this.selectedIndex,
    required this.onDestinationSelected,
  });
  final Widget body;
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Row(
        children: [
          NavigationRail(
            selectedIndex: selectedIndex,
            onDestinationSelected: onDestinationSelected,
            labelType: NavigationRailLabelType.all,
            destinations: [
              const NavigationRailDestination(
                label: Text('Search'),
                icon: Icon(Icons.search_outlined),
              ),
              const NavigationRailDestination(
                label: Text('Updates'),
                icon: Icon(Icons.download_for_offline_outlined),
              ),
              const NavigationRailDestination(
                label: Text('Profile'),
                icon: Icon(Icons.person_outline),
              ),
              const NavigationRailDestination(
                label: Text('Notifications'),
                icon: Icon(Icons.notifications_outlined),
              ),
            ],
          ),
          body,
        ],
      ),
    );
  }
}


// const borderColor = Color(0xFF805306);

// const sidebarColor = Color(0xFFF6A00C);
// const backgroundStartColor = Color(0xFFFFD500);
// const backgroundEndColor = Color(0xFFF6A00C);

// final buttonColors = WindowButtonColors(
//     iconNormal: const Color(0xFF805306),
//     mouseOver: const Color(0xFFF6A00C),
//     mouseDown: const Color(0xFF805306),
//     iconMouseOver: const Color(0xFF805306),
//     iconMouseDown: const Color(0xFFFFD500));

// final closeButtonColors = WindowButtonColors(
//     mouseOver: const Color(0xFFD32F2F),
//     mouseDown: const Color(0xFFB71C1C),
//     iconNormal: const Color(0xFF805306),
//     iconMouseOver: Colors.white);

// class WindowButtons extends StatelessWidget {
//   const WindowButtons({super.key});

//   @override
//   Widget build(BuildContext context) {
//     return Row(
//       children: [
//         MinimizeWindowButton(colors: buttonColors),
//         MaximizeWindowButton(colors: buttonColors),
//         CloseWindowButton(colors: closeButtonColors),
//       ],
//     );
//   }
// }
