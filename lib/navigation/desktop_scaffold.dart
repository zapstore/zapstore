import 'package:flutter/material.dart';

class DesktopScaffold extends StatelessWidget {
  const DesktopScaffold({
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
            minWidth: 120,
            selectedIndex: selectedIndex,
            onDestinationSelected: onDestinationSelected,
            labelType: NavigationRailLabelType.all,
            destinations: [
              const NavigationRailDestination(
                label: Text('Home'),
                icon: Icon(Icons.home_outlined),
                selectedIcon: Icon(Icons.home_filled),
              ),
              const NavigationRailDestination(
                label: Text('Updates'),
                icon: Icon(Icons.download_for_offline_outlined),
                selectedIcon: Icon(Icons.download_for_offline),
              ),
              const NavigationRailDestination(
                label: Text('Settings'),
                icon: Icon(Icons.settings_outlined),
                selectedIcon: Icon(Icons.settings),
              ),
            ],
          ),
          Flexible(
            child: Container(
              constraints: BoxConstraints(
                maxWidth: 768,
              ),
              padding: const EdgeInsets.fromLTRB(16, 20, 16, 0),
              child: body,
            ),
          ),
        ],
      ),
    );
  }
}
