import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/app_card.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/relevant_who_follow_container.dart';
import 'package:zapstore/widgets/rounded_image.dart';
import 'package:zapstore/widgets/user_avatar.dart';
import 'package:zapstore/widgets/zap_receipts.dart';

class DeveloperScreen extends HookConsumerWidget {
  final User model;

  DeveloperScreen({
    required this.model,
    super.key,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    useFuture(useMemoized(() async {
      if (model.pubkey != kZapstorePubkey) {
        await ref.apps.findAll(
          params: {
            'authors': [model.pubkey],
            'ignoreReturn': false,
          },
        );
      }
    }));

    final state = ref.users.watchOne(model.id!, alsoWatch: (_) => {_.apps});

    return SingleChildScrollView(
      physics: AlwaysScrollableScrollPhysics(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              RoundedImage(url: model.avatarUrl, size: 80),
              Gap(10),
              Expanded(
                child: AutoSizeText(
                  model.nameOrNpub,
                  style: TextStyle(fontSize: 34, fontWeight: FontWeight.bold),
                ),
              ),
            ],
          ),
          Gap(20),
          // if (model.apps.first.latestMetadata != null)
          //   ZapReceipts(developer: model),
          RelevantWhoFollowContainer(
            toNpub: model.npub,
            loadingText: 'Loading connections...',
          ),
          Gap(10),
          ElevatedButton(
            onPressed: () {
              final url = Uri.parse('https://npub.world/${model.npub}');
              launchUrl(url);
            },
            child: Text(
              'View ${model.nameOrNpub} on nostr',
            ),
          ),
          Gap(20),
          Text('Signed apps',
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold)),
          Gap(10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (state.hasModel)
                for (final app in model.apps.where((a) => a.isSelfSigned))
                  AppCard(model: app, showUpdate: true),
            ],
          ),
        ],
      ),
    );
  }
}
