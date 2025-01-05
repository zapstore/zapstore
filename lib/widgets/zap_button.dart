import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/navigation/app_initializer.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/signers.dart';
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
    final commentController = TextEditingController();

    if (app.developer.value?.lud16 == null) {
      return Container();
    }

    return ElevatedButton(
      onPressed: nwcConnection.isLoading
          ? null
          : () async {
              if (!nwcConnection.isPresent) {
                await showDialog(
                  // ignore: use_build_context_synchronously
                  context: context,
                  builder: (_) => AlertDialog(
                    title: Text('Connect your wallet').bold,
                    content: NwcContainer(dialogMode: true),
                  ),
                );
                if (!ref.read(nwcConnectionProvider).isPresent) {
                  return;
                }
              }

              final valueRecord = await showDialog<(int, String)>(
                // ignore: use_build_context_synchronously
                context: context,
                builder: (context) {
                  return AlertDialog(
                    title: Text('Choose zap amount').bold,
                    content: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (user == null)
                          SignInButton(
                            publicKeyAllowed: false,
                            minimal: true,
                            signedOutText:
                                '⚠️ If you do not sign in, you will be zapping anonymously',
                          ),
                        TextField(
                          controller: amountController,
                          keyboardType: TextInputType.number,
                          decoration:
                              InputDecoration(hintText: 'Enter amount in sats'),
                        ),
                        Gap(20),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            ElevatedButton(
                              onPressed: () => amountController.text = '21',
                              child: Text('⚡️21'),
                            ),
                            Gap(3),
                            ElevatedButton(
                              onPressed: () => amountController.text = '210',
                              child: Text('⚡️210'),
                            ),
                            Gap(3),
                            ElevatedButton(
                              onPressed: () => amountController.text = '2100',
                              child: Text('⚡️2100'),
                            ),
                          ],
                        ),
                        TextField(
                          controller: commentController,
                          decoration:
                              InputDecoration(hintText: 'Leave a comment'),
                        ),
                      ],
                    ),
                    actions: [
                      TextButton(
                        child: Text('Cancel'),
                        onPressed: () => Navigator.of(context).pop(),
                      ),
                      TextButton(
                        child: Text('Zap').bold,
                        onPressed: () {
                          final record = (
                            int.tryParse(amountController.text) ?? 0,
                            commentController.text
                          );
                          Navigator.of(context).pop(record);
                        },
                      ),
                    ],
                  );
                },
              );

              if (valueRecord == null) {
                return;
              }
              final (amount, comment) = valueRecord;

              if (amount > 0) {
                try {
                  // ignore: use_build_context_synchronously
                  context.showInfo('Zap sent!',
                      description:
                          '$amount sats on their way. Your zap should show up in a few seconds.');

                  // Reload user as we are in a callback and it could have changed
                  final loggedInUser =
                      ref.settings.findOneLocalById('_')!.user.value;
                  if (loggedInUser != null) {
                    await loggedInUser.zap(amount,
                        event: app.latestMetadata!, comment: comment);
                  } else {
                    await anonUser!.zap(amount,
                        event: app.latestMetadata!,
                        signer: pkSigner,
                        comment: comment);
                  }
                } catch (e) {
                  // ignore: use_build_context_synchronously
                  context.showError(
                      title: 'Unable to zap', description: e.toString());
                }
              }
            },
      style: ElevatedButton.styleFrom(
        backgroundColor: Colors.grey[800],
        disabledBackgroundColor: Colors.grey[500],
      ),
      child: Text(
        '⚡️ Zap this release',
        style: TextStyle(
            color: Colors.white, fontWeight: FontWeight.bold, fontSize: 15),
      ),
    );
  }
}
