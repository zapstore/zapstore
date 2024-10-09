import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/nostr_adapter.dart';
import 'package:zapstore/navigation/desktop_scaffold.dart';
import 'package:zapstore/navigation/mobile_scaffold.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/screens/search_screen.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/screens/updates_screen.dart';
import 'package:zapstore/widgets/error_container.dart';

/// Application navigation.
///  - Initializes Go Router, dispatches routes
///  - Initializes Flutter Data (database) and Purplebase (nostr library)
///  - Builds media query dependent scaffold (mobile, desktop)
final appRouter = GoRouter(
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
          navigatorKey: _settingsNavigatorKey,
          routes: [
            GoRoute(
              path: '/settings',
              pageBuilder: (context, state) => NoTransitionPage(
                child: SettingsScreen(),
              ),
            ),
          ],
        ),
      ],
    ),
  ],
);

class ScaffoldWithNestedNavigation extends HookConsumerWidget {
  const ScaffoldWithNestedNavigation({
    Key? key,
    required this.navigationShell,
  }) : super(key: key ?? const ValueKey('ScaffoldWithNestedNavigation'));
  final StatefulNavigationShell navigationShell;

  void onDestinationSelected(int index) {
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
    final initializer = ref.watch(dataLibrariesInitializer);
    return SafeArea(
      child: initializer.when(
        data: (_) => LayoutBuilder(
          builder: (context, constraints) {
            if (constraints.maxWidth < 550) {
              return MobileScaffold(
                body: navigationShell,
                selectedIndex: navigationShell.currentIndex,
                onDestinationSelected: onDestinationSelected,
              );
            } else {
              return DesktopScaffold(
                body: navigationShell,
                selectedIndex: navigationShell.currentIndex,
                onDestinationSelected: onDestinationSelected,
              );
            }
          },
        ),
        error: (e, stack) {
          errorHandler(e, stack);
          return ErrorContainer(exception: e, stack: stack);
        },
        loading: () => const Center(child: CircularProgressIndicator()),
      ),
    );
  }
}

// Data

AppLifecycleListener? _lifecycleListener;

final dataLibrariesInitializer = FutureProvider<void>((ref) async {
  // Initialize Flutter Data
  await ref.read(initializeFlutterData(adapterProvidersMap).future);

  // Initialize relays
  final relay = ref.read(relayMessageNotifierProvider(kAppRelays).notifier);
  final socialRelays =
      ref.read(relayMessageNotifierProvider(kSocialRelays).notifier);
  await Future.wait([relay.initialize(), socialRelays.initialize()]);

  // Trigger app install status calculations
  await ref.apps.appAdapter.updateInstallStatus();
  _lifecycleListener = AppLifecycleListener(
    onStateChange: (state) async {
      if (state == AppLifecycleState.resumed) {
        await ref.apps.appAdapter.updateInstallStatus();
      }
    },
  );

  // Handle deep links
  final appLinksSub = appLinks.uriLinkStream.listen((uri) async {
    if (uri.scheme == "zapstore") {
      final adapter = ref.apps.appAdapter;
      final App? app = await adapter.findOne(uri.host);
      if (app != null) {
        appRouter.go('/updates/details', extra: app);
      }
    }
  });

  ref.onDispose(() {
    _lifecycleListener?.dispose();
    appLinksSub.cancel();
  });
});

// Keys

final _rootNavigatorKey = GlobalKey<NavigatorState>();
final _searchNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'search');
final _updatesNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'updates');
final _settingsNavigatorKey = GlobalKey<NavigatorState>(debugLabel: 'settings');
