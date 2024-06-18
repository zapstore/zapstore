import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/utils/theme.dart';
import 'package:zapstore/widgets/drawer_container.dart';

class MobileScaffold extends HookConsumerWidget {
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
  Widget build(BuildContext context, WidgetRef ref) {
    // TODO Inefficient, should be able to query for this (we need to store app info in database)
    final appsToUpdate =
        ref.apps.watchAll().model.where((a) => a.canUpdate).length;
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
        destinations: [
          NavigationDestination(
            label: 'Home',
            icon: Icon(Icons.home_outlined),
            selectedIcon: Icon(Icons.home_filled),
          ),
          NavigationDestination(
            label: 'Updates',
            icon: appsToUpdate > 0
                ? Badge(
                    label: Text(appsToUpdate.toString()),
                    child: Icon(Icons.update_outlined),
                  )
                : Icon(Icons.update_outlined),
            selectedIcon: appsToUpdate > 0
                ? Badge(
                    label: Text(appsToUpdate.toString()),
                    child: Icon(Icons.update),
                  )
                : Icon(Icons.update),
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
