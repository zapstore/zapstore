import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/sign_in_container.dart';

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
    final user = ref.settings
        .watchOne('_', alsoWatch: (_) => {_.user})
        .model!
        .user
        .value;

    final amountController = TextEditingController();

    return app.developer.value?.lud16 != null
        ? ElevatedButton(
            onPressed: () async {
              final loggedInUser = user ??
                  await showDialog(
                    context: context,
                    builder: (_) => AlertDialog(
                      title: Text(
                        'Sign in to zap',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      content: SignInDialogBox(publicKeyAllowed: false),
                    ),
                  );

              if (loggedInUser == null) {
                return;
              }

              if (nwcConnection.value == null) {
                await showDialog(
                  // ignore: use_build_context_synchronously
                  context: context,
                  builder: (_) => AlertDialog(
                    title: Text(
                      'Connect your wallet to zap',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    content: NostrWalletConnectContainer(dialogMode: true),
                  ),
                );
              }

              if (ref.read(nwcConnectionProvider).value == null) {
                return;
              }

              final amount = await showDialog<int>(
                    // ignore: use_build_context_synchronously
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
                try {
                  // ignore: use_build_context_synchronously
                  context.showInfo('Zap sent!',
                      description: '$amount sats on their way');
                  await loggedInUser.zap(amount, event: app.latestMetadata!);
                } catch (e) {
                  // ignore: use_build_context_synchronously
                  context.showError(
                      title: 'Unable to zap', description: e.toString());
                }
              }
            },
            child: Text('Zap!'),
          )
        : Container();
  }
}
