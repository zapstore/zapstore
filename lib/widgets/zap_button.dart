import 'package:async_button_builder/async_button_builder.dart';
import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
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
    final amountController = TextEditingController();
    final commentController = TextEditingController();

    if (app.developer.value?.lud16 == null) {
      return Container();
    }

    return AsyncButtonBuilder(
      loadingWidget: Text('⚡️ Zapping...').bold,
      builder: (context, child, callback, state) => ElevatedButton(
        onPressed: callback,
        style: ElevatedButton.styleFrom(
          backgroundColor: const Color.fromARGB(255, 60, 60, 54),
          disabledBackgroundColor: Colors.grey[600],
        ),
        child: child,
      ),
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
                    title: Text('Zap this release').bold,
                    content: SizedBox(
                      width: double.maxFinite,
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        spacing: 10,
                        children: [
                          if (app.isInstalled && !app.isUpdated)
                            Text(
                                '⚠️ Zaps will go toward the latest release (${app.latestMetadata!.version}), not the one currently installed (${app.localApp.value!.installedVersion})'),
                          Consumer(
                            builder: (context, ref, _) {
                              final signedInUser =
                                  ref.watch(signedInUserProvider);

                              if (signedInUser == null ||
                                  signedInUser.settings.value!.signInMethod !=
                                      SignInMethod.nip55) {
                                return Column(
                                  children: [
                                    Text(
                                        '⚠️ If you do not sign in (with an external signer like Amber), you will be zapping anonymously'),
                                    SignInButton(
                                      requireNip55: true,
                                      minimal: true,
                                    ),
                                  ],
                                );
                              }
                              return Container();
                            },
                          ),
                          TextField(
                            controller: amountController,
                            keyboardType: TextInputType.number,
                            decoration: InputDecoration(
                              hintText: 'Enter amount in sats',
                            ),
                            inputFormatters: [
                              FilteringTextInputFormatter.digitsOnly
                            ],
                          ),
                          Gap(10),
                          SizedBox(
                            width: double.maxFinite,
                            child: Wrap(
                              alignment: WrapAlignment.start,
                              spacing: 4,
                              children: [
                                ('🤙 21', 21),
                                ('💜 210', 210),
                                ('🤩 420', 420),
                                ('🚀 2100', 2100),
                                ('💯 10K', 10000),
                                ('💎 21K', 21000),
                              ]
                                  .map(
                                    (r) => ElevatedButton(
                                      onPressed: () => amountController.text =
                                          r.$2.toString(),
                                      child: AutoSizeText(
                                        r.$1,
                                        style: TextStyle(fontSize: 12.5),
                                      ),
                                    ),
                                  )
                                  .toList(),
                            ),
                          ),
                          TextField(
                            controller: commentController,
                            decoration: InputDecoration(
                                hintText: 'Add a comment (optional)'),
                          ),
                        ],
                      ),
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
                  // Reload user as we are in a callback and it could have changed
                  final signedInUser =
                      ref.settings.findOneLocalById('_')!.user.value;
                  if (signedInUser != null) {
                    await signedInUser.zap(amount,
                        event: app.latestMetadata!, comment: comment);
                  } else {
                    await anonUser!.zap(amount,
                        event: app.latestMetadata!,
                        signer: pkSigner,
                        comment: comment);
                  }
                  // ignore: use_build_context_synchronously
                  context.showInfo('$amount sat${amount > 1 ? 's' : ''} sent!',
                      description: 'Payment was successful');
                } catch (e) {
                  // ignore: use_build_context_synchronously
                  context.showError('Unable to zap', description: e.toString());
                }
              }
            },
      child: Text(
        '⚡️ Zap this release',
        style: TextStyle(
            color: Colors.white, fontWeight: FontWeight.bold, fontSize: 15),
      ),
    );
  }
}
