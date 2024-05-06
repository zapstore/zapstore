import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:path_provider/path_provider.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/screens/updates_screen.dart';
import 'package:zapstore/widgets/app_drawer.dart';
import 'package:zapstore/screens/search_screen.dart';

void main() {
  runApp(
    ProviderScope(
      overrides: [
        localStorageProvider.overrideWithValue(
          LocalStorage(
            baseDirFn: () async {
              final path = (await getApplicationSupportDirectory()).path;
              print('initializing local storage at $path');
              return path;
            },
            clear: LocalStorageClearStrategy.whenError,
          ),
        )
      ],
      child: const ZapstoreApp(),
    ),
  );
}

const kBackgroundColor = Color.fromARGB(255, 6, 6, 6);

class ZapstoreApp extends StatelessWidget {
  const ZapstoreApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      routerConfig: goRouter,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        primarySwatch: Colors.lightBlue,
        brightness: Brightness.dark,
        fontFamily: 'Inter',
        useMaterial3: true,
        scaffoldBackgroundColor: kBackgroundColor,
        visualDensity: VisualDensity.adaptivePlatformDensity,
      ),
    );
  }
}

// private navigators
final _rootNavigatorKey = GlobalKey<NavigatorState>();
final _searchNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'search');
final _updatesNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'updates');
final _settingsNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'settings');

final goRouter = GoRouter(
  initialLocation: '/',
  // * Passing a navigatorKey causes an issue on hot reload:
  // * https://github.com/flutter/flutter/issues/113757#issuecomment-1518421380
  // * However it's still necessary otherwise the navigator pops back to
  // * root on hot reload
  navigatorKey: _rootNavigatorKey,
  routes: [
    StatefulShellRoute.indexedStack(
      builder: (context, state, navigationShell) {
        return ScaffoldWithNestedNavigation(navigationShell: navigationShell);
      },
      branches: [
        StatefulShellBranch(
          navigatorKey: _searchNavigatorKey,
          routes: [
            GoRoute(
              path: '/',
              builder: (context, state) => SearchScreen(),
              routes: [
                GoRoute(
                  path: 'details',
                  builder: (context, state) =>
                      AppDetailScreen(model: state.extra as App),
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
              builder: (context, state) => UpdatesScreen(),
            ),
          ],
        ),
        StatefulShellBranch(
          navigatorKey: _settingsNavigatorKey,
          routes: [
            GoRoute(
              path: '/settings',
              pageBuilder: (context, state) => NoTransitionPage(
                child: Center(
                  child: SettingsScreen(),
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
  await ref.read(initializeFlutterData(adapterProvidersMap).future);
  ref
      .read(relayMessageNotifierProvider.notifier)
      .initialize(['wss://relay.zap.store', 'wss://relay.nostr.band']);
});

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
          if (constraints.maxWidth < 550) {
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
      body: Padding(
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 0),
        child: initializer.when(
          data: (_) => body,
          error: (e, _) => const Text('error'),
          loading: () => const Center(child: CircularProgressIndicator()),
        ),
      ),
      bottomNavigationBar: NavigationBar(
        height: 60,
        backgroundColor: kBackgroundColor,
        indicatorColor: Colors.transparent,
        selectedIndex: selectedIndex,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysHide,
        destinations: const [
          NavigationDestination(
            label: 'Home',
            icon: Icon(Icons.home_outlined),
            selectedIcon: Icon(Icons.home_filled),
          ),
          NavigationDestination(
            label: 'Updates',
            icon: Icon(Icons.download_for_offline_outlined),
            selectedIcon: Icon(Icons.download_for_offline),
          ),
          NavigationDestination(
            label: 'Settings',
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
          ),
        ],
        onDestinationSelected: onDestinationSelected,
      ),
      drawer: Drawer(
        child: AppDrawer(),
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
            ],
          ),
          body,
        ],
      ),
    );
  }
}
