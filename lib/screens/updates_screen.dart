import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/widgets/app_card.dart';

class UpdatesScreen extends HookConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // TODO: Workaround for bug in watchAll (when remote=true)
    final snapshot = useFuture(useMemoized(
        () => ref.apps.findAll(remote: true, params: {'installed': true})));
    final state = ref.apps.watchAll();
    ref.localApps.watchAll();

    final updatableApps = state.model
        .where((app) => app.canUpdate)
        .toList()
        .sorted((a, b) => (a.name ?? '').compareTo(b.name ?? ''));

    final updatedApps = state.model
        .where((app) => app.isUpdated)
        .toList()
        .sorted((a, b) => (a.name ?? '').compareTo(b.name ?? ''));

    return RefreshIndicator(
      onRefresh: () {
        return ref.apps.findAll(remote: true, params: {'installed': true});
      },
      child: SingleChildScrollView(
        physics: AlwaysScrollableScrollPhysics(),
        child: Column(
          key: UniqueKey(),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.start,
              children: [
                Text('Installed apps',
                    style:
                        TextStyle(fontSize: 30, fontWeight: FontWeight.bold)),
                Gap(20),
                if (snapshot.connectionState == ConnectionState.waiting)
                  SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator()),
              ],
            ),
            if (updatableApps.isNotEmpty)
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Gap(20),
                  Text(
                    '${updatableApps.length} update${updatableApps.length > 1 ? 's' : ''} available'
                        .toUpperCase(),
                    style: TextStyle(
                      fontSize: 16,
                      letterSpacing: 3,
                      fontWeight: FontWeight.w300,
                    ),
                  ),
                  //
                  Gap(10),
                  for (final app in updatableApps)
                    AppCard(app: app, showUpdate: true),
                ],
              ),
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
            for (final app in updatedApps) AppCard(app: app),
          ],
        ),
      ),
    );
  }
}
