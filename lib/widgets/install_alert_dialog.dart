import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/app_drawer.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/wot_container.dart';

class InstallAlertDialog extends ConsumerWidget {
  const InstallAlertDialog({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.settings
        .watchOne('_', alsoWatch: (_) => {_.user})
        .model!
        .user
        .value;

    return AlertDialog(
      elevation: 10,
      title: Text(
        'Are you sure you want to install ${app.name}?',
        style: TextStyle(fontWeight: FontWeight.bold),
      ),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
                'By installing this app you are trusting the signer now and for all future updates. Make sure you know who they are.'),
            Gap(20),
            if (app.signer.value != null)
              AuthorContainer(
                  user: app.signer.value!, text: 'Signed by', oneLine: true),
            Gap(20),
            if (app.signer.value != null)
              WebOfTrustContainer(
                fromNpub: user?.npub ?? kFranzapNpub,
                toNpub: app.signer.value!.npub,
              ),
            if (user == null)
              LoginContainer(
                minimal: true,
                labelText: 'Log in to view your own web of trust',
              ),
            Gap(20),
            if (app.latestMetadata?.urls.isNotEmpty ?? false)
              RichText(
                text: WidgetSpan(
                  child: Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      Text('This app will be downloaded from '),
                      Text(
                        Uri.parse(app.latestMetadata!.urls.first).host,
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          decoration: TextDecoration.underline,
                        ),
                      ),
                      Text(' and verified.'),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
      actions: [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: TextButton(
            onPressed: () {
              app.install();
              // NOTE: can't use context.pop()
              Navigator.of(context).pop();
            },
            child: user != null
                ? Text('Install', style: TextStyle(fontWeight: FontWeight.bold))
                : Text(
                    'I trust the signer, install the app',
                    textAlign: TextAlign.right,
                  ),
          ),
        ),
        TextButton(
          onPressed: () {
            // NOTE: can't use context.pop()
            Navigator.of(context).pop();
          },
          child: Text('Go back'),
        ),
      ],
    );
  }
}
