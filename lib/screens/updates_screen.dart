import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/release.dart';
import 'package:zapstore/screens/app_detail_screen.dart';
import 'package:zapstore/widgets/card.dart';
import 'package:zapstore/widgets/pill_widget.dart';

final installedAppsStateProvider = StateNotifierProvider.autoDispose<
    DataStateNotifier<List<App>>, DataState<List<App>>>((ref) {
  final n = DataStateNotifier(data: DataState<List<App>>([], isLoading: true));
  ref.apps.appAdapter
      .getInstalledApps()
      .then((apps) => n.updateWith(model: apps.toList(), isLoading: false));
  return n;
});

class UpdatesScreen extends HookConsumerWidget {
  const UpdatesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(installedAppsStateProvider);

    if (state.isLoading) {
      return Center(
        child: CircularProgressIndicator(),
      );
    }

    final updatableApps = state.model.where((app) => app.canUpdate).toList();
    final updatedApps = state.model.where((app) => app.isUpdated).toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('Installed apps',
                style: TextStyle(fontSize: 30, fontWeight: FontWeight.bold)),
            // Expanded(
            //   child: TextButton(
            //     style: TextButton.styleFrom(
            //       foregroundColor: Colors.lightBlue,
            //     ),
            //     onPressed: () {},
            //     child: const Text('Update All'),
            //   ),
            // ),
          ],
        ),
        Gap(20),
        if (updatableApps.isNotEmpty)
          Text(
            '${updatableApps.length} updates available'.toUpperCase(),
            style: TextStyle(
              fontSize: 16,
              letterSpacing: 3,
              fontWeight: FontWeight.w300,
            ),
          ),
        Gap(10),
        if (updatableApps.isNotEmpty)
          ListView.builder(
            shrinkWrap: true,
            itemCount: updatableApps.length,
            itemBuilder: (context, index) {
              final app = updatableApps[index];
              return UpdatesAppCard(app: app);
            },
          ),
        Gap(40),
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
        ListView.builder(
          shrinkWrap: true,
          itemCount: updatedApps.length,
          itemBuilder: (context, index) {
            final app = updatedApps[index];
            return UpdatesAppCard(app: app);
          },
        ),
      ],
    );
  }
}

class UpdatesAppCard extends StatelessWidget {
  const UpdatesAppCard({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: EdgeInsets.only(top: 8, bottom: 8),
      elevation: 0,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.start,
          children: [
            CircularImage(
              url: app.icons.first,
              size: 80,
              radius: 25,
            ),
            Gap(16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  AutoSizeText(
                    app.name!,
                    minFontSize: 16,
                    style: TextStyle(fontSize: 23, fontWeight: FontWeight.bold),
                    overflow: TextOverflow.ellipsis,
                    maxLines: 1,
                  ),
                  // NOTE: we MUST call getInstalledApps() in order to use currentVersion
                  if (app.currentVersion != null)
                    PillWidget(text: app.currentVersion!),
                  if (app.currentVersion != app.releases.latest!.version)
                    PillWidget(text: app.releases.latest!.version),
                ],
              ),
            ),
            if (app.currentVersion != app.releases.latest!.version)
              Expanded(child: InstallButton(app: app))
          ],
        ),
      ),
    );
  }
}
