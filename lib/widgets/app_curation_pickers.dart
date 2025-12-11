import 'package:flutter/material.dart';
import 'package:models/models.dart';
import 'package:zapstore/widgets/profiles_rich_text.dart';

class AppPickersContainer extends StatelessWidget {
  const AppPickersContainer({super.key, required this.app, this.author});

  final App app;
  final Profile? author;

  @override
  Widget build(BuildContext context) {
    final appPacks = app.appPacks.toList();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (appPacks.isNotEmpty) ...[
          _AppPackPickers(appPacks: appPacks),
          const SizedBox(height: 16),
        ],
      ],
    );
  }
}

class _AppPackPickers extends StatelessWidget {
  const _AppPackPickers({required this.appPacks});

  final List<AppPack> appPacks;

  @override
  Widget build(BuildContext context) {
    final seenPubkeys = <String>{};
    final curatorProfiles = appPacks
        .map((pack) => pack.author.value)
        .where((profile) => profile != null && seenPubkeys.add(profile.pubkey))
        .cast<Profile>()
        .toList();

    if (curatorProfiles.isEmpty) {
      return const SizedBox.shrink();
    }

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: ProfilesRichText(
        profiles: curatorProfiles,
        trailingText: ' picked this app',
        maxProfilesToDisplay: 3,
        avatarRadius: 12,
        textStyle: Theme.of(
          context,
        ).textTheme.bodySmall?.copyWith(color: Colors.grey[600]),
      ),
    );
  }
}
