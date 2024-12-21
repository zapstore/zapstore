import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/navigation/router.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/zap.dart';

import '../utils/nwc.dart';

class ZapButton extends HookConsumerWidget {
  ZapButton({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final nwcConnection = ref.watch(nwcConnectionProvider);
    final zapStatus = ref.watch(zapProvider);
    final user = ref.settings
        .watchOne('_', alsoWatch: (_) => {_.user})
        .model!
        .user
        .value;

    final amountController = TextEditingController();

    var text = 'Zap the dev ⚡';
    switch (zapStatus) {
      case "zapping":
        text = 'Zapping... ⚡⚡⚡';
      case "zapped":
        text = 'Zapped!';
    }

    return app.developer.value != null
        ? ElevatedButton(
            onPressed: () async {
              if (nwcConnection == null) {
                context.showError(title: "need an NWC connection URI set");
                appRouter.go('/settings');
                return;
              }
              User? developer = app.developer.value;
              if (developer == null) {
                context.showError(title: "no dev set");
                return;
              }
              if (developer.lud16 == null) {
                context.showError(
                    title: "${developer.nameOrNpub} has no LN address :(");
                return;
              }
              if (user == null) {
                context.showError(title: "must be logged in to sign zaps");
                appRouter.go('/settings');
                return;
              }
              int amount = await showDialog<int>(
                    context: context,
                    builder: (BuildContext context) {
                      return AlertDialog(
                        title: Text('Choose Zap Amount'),
                        content: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            TextField(
                              controller: amountController,
                              keyboardType: TextInputType.number,
                              decoration: InputDecoration(
                                  hintText: "Enter amount in sats"),
                            ),
                            SizedBox(height: 20),
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                              children: [
                                ElevatedButton(
                                  onPressed: () =>
                                      Navigator.of(context).pop(21),
                                  child: Text('⚡ 21'),
                                ),
                                Gap(10),
                                ElevatedButton(
                                  onPressed: () =>
                                      Navigator.of(context).pop(210),
                                  child: Text('⚡ 210'),
                                ),
                                Gap(10),
                                ElevatedButton(
                                  onPressed: () =>
                                      Navigator.of(context).pop(2100),
                                  child: Text('⚡ 2100'),
                                ),
                              ],
                            ),
                          ],
                        ),
                        actions: <Widget>[
                          TextButton(
                            child: Text('Cancel'),
                            onPressed: () {
                              Navigator.of(context)
                                  .pop(); // Dismiss the dialog without returning an amount
                            },
                          ),
                          TextButton(
                            child: Text('Zap'),
                            onPressed: () {
                              Navigator.of(context).pop(
                                  int.tryParse(amountController.text) ?? 0);
                            },
                          ),
                        ],
                      );
                    },
                  ) ??
                  0;
              if (amount > 0) {
                final lnurl = developer.lud16!;
                await ref.read(zapProvider.notifier).zap(
                    nwcConnection: nwcConnection,
                    user: user,
                    lnurl: lnurl,
                    eventId: app.latestMetadata != null ? app.latestMetadata!.id
                        .toString() : app.latestRelease!.id.toString(),
                    amount: amount,
                    pubKey: developer.pubkey);
              }
            },
            child: Text(text))
        : Container();
  }
}
