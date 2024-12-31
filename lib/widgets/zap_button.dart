import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/settings.dart';
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

    // We use value as default text, for progress only interested in loading/error substates
    final zapButtonText = switch (zapStatus) {
      AsyncLoading() => 'Zapping... ⚡⚡⚡',
      AsyncError() => 'Error zapping!',
      AsyncValue() => 'Zap the dev ⚡',
    };

    // Show toast if could not zap
    useMemoized(() {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (zapStatus.value != null) {
          // final receipt = await zapStatus.value!.zapReceipt;
          // TODO: zapStatus should include the amount of sats without needing async
          // I could read zapReceiptsNotifier's state, but how I know it's this one?
          context.showInfo('Zapped!',
              description: 'You just sent the dev some sats');
        }
        if (zapStatus.hasError) {
          context.showError(
              title: 'Could not zap',
              description: 'Error sending zap: ${zapStatus.error}');
        }
      });
    }, [zapStatus.value, zapStatus.error]);

    return app.developer.value != null
        ? AsyncButtonBuilder(
            loadingWidget: SizedBox(
                width: 14, height: 14, child: CircularProgressIndicator()),
            onPressed: () async {
              if (user == null) {
                context.showError(
                    title: 'Not signed in',
                    description: 'Please sign in to zap');
                return;
              }

              if (nwcConnection.value == null) {
                context.showError(
                    title: 'No wallet connected',
                    description: 'Please connect your NWC wallet');
                appRouter.go('/settings');
                return;
              }

              final developer = app.developer.value!;
              if (developer.lud16 == null) {
                context.showError(
                    title: 'Unable to zap',
                    description:
                        'The developer (${developer.nameOrNpub}) does not have a Lightning address');
                return;
              }

              final amount = await showDialog<int>(
                    context: context,
                    builder: (context) {
                      return AlertDialog(
                        title: Text('Choose zap amount'),
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
                        actions: [
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
                // User should be able to sign any event
                // Proposed API:
                await user.zap(amount, event: app.latestMetadata!);

                // final lnurl = developer.lud16!;
                // await ref.read(zapProvider.notifier).zap(
                //     user: user,
                //     lnurl: lnurl,
                //     eventId: app.latestMetadata!.id.toString(),
                //     amount: amount,
                //     pubKey: developer.pubkey);
              }
            },
            builder: (context, child, callback, state) {
              return ElevatedButton(onPressed: callback, child: child);
            },
            child: Text(zapButtonText),
          )
        : Container();
  }
}
