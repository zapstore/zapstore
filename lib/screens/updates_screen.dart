import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/spinning_logo.dart';

class UpdatesScreen extends HookConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(updatesProvider);

    final (updatedApps, updatableApps, unupdatableApps) =
        state.value ?? ([], [], []);

    return SingleChildScrollView(
      physics: AlwaysScrollableScrollPhysics(),
      child: Column(
        key: UniqueKey(),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Installed apps',
              style: TextStyle(fontSize: 30, fontWeight: FontWeight.bold)),
          if (updatedApps.isEmpty && updatableApps.isEmpty)
            SpinningLogo(size: 80),
          if (updatableApps.isNotEmpty)
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Gap(20),
                Text(
                  (updatableApps.length > 1
                          ? '${updatableApps.length} updates available'
                          : 'One update available')
                      .toUpperCase(),
                  style: TextStyle(
                    fontSize: 16,
                    letterSpacing: 3,
                    fontWeight: FontWeight.w300,
                  ),
                ),
                Gap(10),
                for (final app in updatableApps)
                  AppCard(model: app, showUpdate: true),
              ],
            ),
          Gap(20),
          if (unupdatableApps.isNotEmpty)
            Text(
              'Disabled updates'.toUpperCase(),
              style: TextStyle(
                fontSize: 16,
                letterSpacing: 3,
                fontWeight: FontWeight.w300,
              ),
            ),
          Gap(10),
          for (final app in unupdatableApps)
            AppCard(model: app, showUpdate: true),
          Gap(20),
          if (updatedApps.isNotEmpty)
            Text(
              'Up to date'.toUpperCase(),
              style: TextStyle(
                fontSize: 16,
                letterSpacing: 3,
                fontWeight: FontWeight.w300,
              ),
            ),
          Gap(10),
          for (final app in updatedApps) AppCard(model: app),
        ],
      ),
    );
  }
}

// Provider

class UpdatesNotifier extends AsyncNotifier<(List<App>, List<App>, List<App>)> {
  @override
  Future<(List<App>, List<App>, List<App>)> build() async {
    // This provider is quite inefficient, as it watches everything
    // and always triggers as there's no proper equality for the return type
    // There is also no good way to manage state since returning a value
    // is necessary. Should migrate to a StateNotifier but there's an
    // issue with Flutter Data's autodipose providers in this context.
    final appsState = ref.apps.watchAll();
    final localAppsState = ref.localApps.watchAll();

    if (!appsState.hasModel || !localAppsState.hasModel) {
      return (<App>[], <App>[], <App>[]);
    }

    final updatableApps = appsState.model
        .where((app) => app.canUpdate && !app.hasCertificateMismatch)
        .sortByName();

    final updatedApps = appsState.model
        .where((app) => app.isUpdated || app.isDowngrade)
        .sortByName();

    final unupdatableApps =
        appsState.model.where((app) => app.hasCertificateMismatch).sortByName();

    return (updatedApps, updatableApps, unupdatableApps);
  }
}

extension on Iterable<App> {
  List<App> sortByName() => toList().sorted((a, b) =>
      (a.name?.toLowerCase() ?? '').compareTo(b.name?.toLowerCase() ?? ''));
}

final updatesProvider =
    AsyncNotifierProvider<UpdatesNotifier, (List<App>, List<App>, List<App>)>(
        UpdatesNotifier.new);
