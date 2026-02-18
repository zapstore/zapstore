import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/services/updates_service.dart';
import 'package:zapstore/widgets/common/badges.dart';
import '../widgets/common/profile_avatar.dart';
import '../theme.dart';

/// Main scaffold with bottom navigation that adapts to screen size
class MainScaffold extends StatelessWidget {
  const MainScaffold({super.key, required this.navigationShell});

  final StatefulNavigationShell navigationShell;

  /// Handle bottom navigation tap
  void _onBottomNavTap(
    BuildContext context,
    StatefulNavigationShell navigationShell,
    int index,
  ) {
    if (navigationShell.currentIndex == index) {
      // Already on this tab, pop to root of the navigation stack
      final router = GoRouter.of(context);
      while (router.canPop()) {
        router.pop();
      }
    } else {
      // Switch to the selected tab
      navigationShell.goBranch(index);
    }
  }

  @override
  Widget build(BuildContext context) {
    final router = GoRouter.of(context);

    // Capture root navigator here so the PopScope callback can reach it.
    // We need this to close dialogs (e.g. the fullscreen image viewer) that
    // are pushed onto the root navigator via showDialog(useRootNavigator: true)
    // before falling through to go_router branch navigation.
    final rootNavigator = Navigator.of(context, rootNavigator: true);

    return PopScope(
      canPop: false, // Never let system close the app via back gesture
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) {
          // If there is a dialog/modal sitting on the root navigator (e.g. the
          // fullscreen screenshot viewer), pop it first. The easy_image_viewer
          // package uses the deprecated WillPopScope internally, which is
          // bypassed by the predictive back gesture on Android 14+; without
          // this check the gesture would instead pop the go_router route
          // (the app detail screen) while leaving the dialog visible on top.
          if (rootNavigator.canPop()) {
            rootNavigator.pop();
            return;
          }

          // Check if we can pop within the current shell branch
          // canPop() may return true for shell-level navigation, so we need
          // to verify we're not at a branch root before popping
          final currentLocation =
              router.routerDelegate.currentConfiguration.uri.path;
          final isAtBranchRoot = kBranchRoots.contains(currentLocation);

          if (!isAtBranchRoot && router.canPop()) {
            router.pop(); // Go back within the branch
          } else if (navigationShell.currentIndex != 0) {
            // At root of non-home tab, go to home (search) tab
            navigationShell.goBranch(0);
          }
          // At home tab root, do nothing (don't close the app)
        }
      },
      child: LayoutBuilder(
        builder: (context, constraints) {
          // Use mobile layout for screens < 550px width
          if (constraints.maxWidth < 550) {
            return MobileScaffold(
              navigationShell: navigationShell,
              onNavTap: _onBottomNavTap,
            );
          } else {
            return DesktopScaffold(
              navigationShell: navigationShell,
              onNavTap: _onBottomNavTap,
            );
          }
        },
      ),
    );
  }
}

/// Mobile scaffold with bottom navigation
class MobileScaffold extends ConsumerWidget {
  const MobileScaffold({
    super.key,
    required this.navigationShell,
    required this.onNavTap,
  });

  final StatefulNavigationShell navigationShell;
  final void Function(BuildContext, StatefulNavigationShell, int) onNavTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pubkey = ref.watch(Signer.activePubkeyProvider);
    final profile = ref.watch(
      Signer.activeProfileProvider(
        const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    // Watch categorized to keep poller alive (poller is watched by categorized)
    final categorized = ref.watch(categorizedUpdatesProvider);
    final poller = ref.watch(updatePollerProvider);
    final updateCount = ref.watch(updateCountProvider);
    final isLoadingUpdates = categorized.showSkeleton || poller.isChecking;

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: AppGradients.darkBackgroundGradient,
        ),
        child: SafeArea(child: navigationShell),
      ),
      bottomNavigationBar: Container(
        decoration: BoxDecoration(
          gradient: AppGradients.darkSurfaceGradient,
          border: Border(
            top: BorderSide(
              color: Theme.of(
                context,
              ).colorScheme.outline.withValues(alpha: 0.15),
              width: 0.5,
            ),
          ),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.2),
              blurRadius: 8,
              offset: const Offset(0, -2),
            ),
          ],
        ),
        child: MediaQuery.removePadding(
          context: context,
          removeBottom: true,
          child: Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: BottomNavigationBar(
              currentIndex: navigationShell.currentIndex,
              type: BottomNavigationBarType.fixed,
              onTap: (index) => onNavTap(context, navigationShell, index),
              showSelectedLabels: false,
              showUnselectedLabels: false,
              elevation: 0,
              backgroundColor: Colors.transparent,
              selectedItemColor: Theme.of(context).colorScheme.primary,
              unselectedItemColor: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.5),
              selectedIconTheme: IconThemeData(
                size: 26,
                color: Theme.of(context).colorScheme.primary,
              ),
              unselectedIconTheme: IconThemeData(
                size: 22,
                color: Theme.of(
                  context,
                ).colorScheme.onSurface.withValues(alpha: 0.5),
              ),
              items: [
                BottomNavigationBarItem(
                  icon: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
                    decoration: navigationShell.currentIndex == 0
                        ? BoxDecoration(
                            color: Theme.of(
                              context,
                            ).colorScheme.primary.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(16),
                          )
                        : null,
                    child: const Icon(Icons.search_rounded),
                  ),
                  label: 'Search',
                ),
                BottomNavigationBarItem(
                  icon: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
                    decoration: navigationShell.currentIndex == 1
                        ? BoxDecoration(
                            color: Theme.of(
                              context,
                            ).colorScheme.primary.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(16),
                          )
                        : null,
                    child: Stack(
                      clipBehavior: Clip.none,
                      children: [
                        const Icon(Icons.update),
                        if (isLoadingUpdates)
                          Positioned(
                            right: -8,
                            top: -8,
                            child: BadgePill(
                              child: const SizedBox(
                                width: 8,
                                height: 8,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white,
                                ),
                              ),
                            ),
                          )
                        else if (updateCount > 0)
                          Positioned(
                            right: -8,
                            top: -8,
                            child: CountBadge(count: updateCount),
                          ),
                      ],
                    ),
                  ),
                  label: 'Updates',
                ),
                BottomNavigationBarItem(
                  icon: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
                    decoration: navigationShell.currentIndex == 2
                        ? BoxDecoration(
                            color: Theme.of(
                              context,
                            ).colorScheme.primary.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(16),
                          )
                        : null,
                    child: pubkey != null
                        ? Container(
                            decoration: navigationShell.currentIndex == 2
                                ? BoxDecoration(
                                    border: Border.all(
                                      color: Theme.of(
                                        context,
                                      ).colorScheme.primary,
                                      width: 2,
                                    ),
                                    borderRadius: BorderRadius.circular(16),
                                  )
                                : null,
                            child: ProfileAvatar(
                              profile: profile,
                              pubkey: pubkey,
                              radius: 14,
                            ),
                          )
                        : const Icon(Icons.person_rounded),
                  ),
                  label: 'Profile',
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Desktop scaffold with navigation rail
class DesktopScaffold extends ConsumerWidget {
  const DesktopScaffold({
    super.key,
    required this.navigationShell,
    required this.onNavTap,
  });

  final StatefulNavigationShell navigationShell;
  final void Function(BuildContext, StatefulNavigationShell, int) onNavTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pubkey = ref.watch(Signer.activePubkeyProvider);
    final profile = ref.watch(
      Signer.activeProfileProvider(
        const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    // Watch categorized to keep poller alive (poller is watched by categorized)
    final categorized = ref.watch(categorizedUpdatesProvider);
    final poller = ref.watch(updatePollerProvider);
    final updateCount = ref.watch(updateCountProvider);
    final isLoadingUpdates = categorized.showSkeleton || poller.isChecking;

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: AppGradients.darkBackgroundGradient,
        ),
        child: SafeArea(
          child: Row(
            children: [
              Container(
                decoration: BoxDecoration(
                  gradient: AppGradients.darkSurfaceGradient,
                  border: Border(
                    right: BorderSide(
                      color: Theme.of(
                        context,
                      ).colorScheme.outline.withValues(alpha: 0.15),
                      width: 0.5,
                    ),
                  ),
                ),
                child: NavigationRail(
                  backgroundColor: Colors.transparent,
                  selectedIndex: navigationShell.currentIndex,
                  onDestinationSelected: (index) =>
                      onNavTap(context, navigationShell, index),
                  labelType: NavigationRailLabelType.all,
                  destinations: [
                    const NavigationRailDestination(
                      icon: Icon(Icons.search),
                      label: Text('Search'),
                    ),
                    NavigationRailDestination(
                      icon: Stack(
                        clipBehavior: Clip.none,
                        children: [
                          const Icon(Icons.update),
                          if (isLoadingUpdates)
                            Positioned(
                              right: -8,
                              top: -8,
                              child: BadgePill(
                                child: const SizedBox(
                                  width: 8,
                                  height: 8,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: Colors.white,
                                  ),
                                ),
                              ),
                            )
                          else if (updateCount > 0)
                            Positioned(
                              right: -8,
                              top: -8,
                              child: CountBadge(count: updateCount),
                            ),
                        ],
                      ),
                      label: const Text('Updates'),
                    ),
                    NavigationRailDestination(
                      icon: pubkey != null
                          ? Container(
                              decoration: navigationShell.currentIndex == 2
                                  ? BoxDecoration(
                                      border: Border.all(
                                        color: Theme.of(
                                          context,
                                        ).colorScheme.primary,
                                        width: 2,
                                      ),
                                      borderRadius: BorderRadius.circular(16),
                                    )
                                  : null,
                              child: ProfileAvatar(
                                profile: profile,
                                pubkey: pubkey,
                                radius: 14,
                              ),
                            )
                          : const Icon(Icons.person),
                      label: const Text('Profile'),
                    ),
                  ],
                ),
              ),
              Expanded(child: navigationShell),
            ],
          ),
        ),
      ),
    );
  }
}
