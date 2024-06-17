import 'package:flutter/material.dart';
import 'package:zapstore/utils/theme.dart';
import 'package:zapstore/widgets/drawer_container.dart';

class MobileScaffold extends StatelessWidget {
  const MobileScaffold({
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
      key: scaffoldKey,
      body: Padding(
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 0),
        child: body,
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
            icon: Badge(
              label: Text('3'),
              child: Icon(Icons.update_outlined),
            ),
            selectedIcon: Badge(
              label: Text('3'),
              child: Icon(Icons.update),
            ),
            // selectedIcon: Icon(Icons.update),
          ),
          NavigationDestination(
            label: 'Settings',
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
          ),
        ],
        onDestinationSelected: onDestinationSelected,
      ),
      drawer: DrawerContainer(),
    );
  }
}

final scaffoldKey = GlobalKey<ScaffoldState>();
