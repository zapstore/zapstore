import 'package:flutter/material.dart';
import 'package:bitsdojo_window/bitsdojo_window.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:ndk/ndk.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/screens/profile_screen.dart';
import 'package:zapstore/screens/search_screen.dart';

void main() {
  appWindow.size = const Size(400, 700);
  runApp(
    ProviderScope(
      overrides: [
        configureRepositoryLocalStorage(
            clear: LocalStorageClearStrategy.always),
      ],
      child: const ZapStoreApp(),
    ),
  );
  appWindow.show();
  doWhenWindowReady(() {
    final win = appWindow;
    const initialSize = Size(400, 700);
    win.minSize = initialSize;
    win.size = initialSize;
    win.alignment = Alignment.center;
    // win.title = "Custom window with Flutter";
    win.show();
  });
}

const borderColor = Color(0xFF805306);

final newInitializer = FutureProvider<void>((ref) async {
  await ref.read(repositoryInitializerProvider.future);
  await ref
      .read(frameProvider.notifier)
      .initialize({'wss://relay.damus.io', 'wss://relay.nostr.band'});
});

class ZapStoreApp extends HookConsumerWidget {
  const ZapStoreApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final initializer = ref.watch(newInitializer);
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(),
      home: Scaffold(
        body: Column(
          children: [
            WindowTitleBarBox(
              child: Row(
                children: [
                  Expanded(child: MoveWindow()),
                  // const WindowButtons()
                ],
              ),
            ),
            Expanded(
              child: Center(
                child: initializer.when(
                  data: (_) => NavigationRailPage(),
                  error: (err, stack) => Text('error $err'),
                  loading: () => CircularProgressIndicator(),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class NavigationRailPage extends HookConsumerWidget {
  const NavigationRailPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selectedIndex = useState(0);

    final width = MediaQuery.of(context).size.width;
    final bool isSmallScreen = width < 600;

    return Scaffold(
      bottomNavigationBar: isSmallScreen
          ? BottomNavigationBar(
              items: _navBarItems,
              currentIndex: selectedIndex.value,
              onTap: (int index) {
                selectedIndex.value = index;
              })
          : null,
      body: Row(
        children: [
          if (!isSmallScreen)
            NavigationRail(
              selectedIndex: selectedIndex.value,
              onDestinationSelected: (int index) {
                selectedIndex.value = index;
              },
              extended: !isSmallScreen,
              destinations: _navBarItems
                  .map((item) => NavigationRailDestination(
                      icon: item.icon,
                      selectedIcon: item.activeIcon,
                      label: Text(item.label!)))
                  .toList(),
            ),
          // const VerticalDivider(thickness: 1, width: 1),
          switch (selectedIndex.value) {
            0 => SearchScreen(),
            1 => const Image(image: AssetImage('assets/images/logo.png')),
            2 => ProfileScreen(),
            _ => throw Error(),
          },
        ],
      ),
    );
  }
}

const _navBarItems = [
  BottomNavigationBarItem(
    icon: Icon(Icons.search_outlined),
    activeIcon: Icon(Icons.search_rounded),
    label: 'Search',
  ),
  BottomNavigationBarItem(
    icon: Icon(Icons.download_for_offline_outlined),
    activeIcon: Icon(Icons.download_for_offline_rounded),
    label: 'Updates',
  ),
  BottomNavigationBarItem(
    icon: Icon(Icons.person_outline_rounded),
    activeIcon: Icon(Icons.person_rounded),
    label: 'Profile',
  ),
];

const sidebarColor = Color(0xFFF6A00C);
const backgroundStartColor = Color(0xFFFFD500);
const backgroundEndColor = Color(0xFFF6A00C);

final buttonColors = WindowButtonColors(
    iconNormal: const Color(0xFF805306),
    mouseOver: const Color(0xFFF6A00C),
    mouseDown: const Color(0xFF805306),
    iconMouseOver: const Color(0xFF805306),
    iconMouseDown: const Color(0xFFFFD500));

final closeButtonColors = WindowButtonColors(
    mouseOver: const Color(0xFFD32F2F),
    mouseDown: const Color(0xFFB71C1C),
    iconNormal: const Color(0xFF805306),
    iconMouseOver: Colors.white);

class WindowButtons extends StatelessWidget {
  const WindowButtons({super.key});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        MinimizeWindowButton(colors: buttonColors),
        MaximizeWindowButton(colors: buttonColors),
        CloseWindowButton(colors: closeButtonColors),
      ],
    );
  }
}
