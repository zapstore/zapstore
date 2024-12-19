import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/sign_in_container.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/wot_container.dart';

class InstallAlertDialog extends HookConsumerWidget {
  const InstallAlertDialog({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final trustedSignerNotifier = useState(false);
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
                  user: app.signer.value!,
                  beforeText: 'Signed by',
                  oneLine: true),
            Gap(20),
            if (app.signer.value != null)
              WebOfTrustContainer(
                fromNpub: user?.npub ?? kFranzapPubkey.npub,
                toNpub: app.signer.value!.npub,
              ),
            if (user == null)
              SignInButton(
                label: 'Sign in to view your web of trust',
                minimal: true,
              ),
            Gap(16),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Switch(
                  value: trustedSignerNotifier.value,
                  onChanged: (value) {
                    trustedSignerNotifier.value = value;
                  },
                ),
                Gap(4),
                Expanded(
                  child: Text(
                    'Do not ask again for ${app.signer.value!.name} apps',
                  ),
                )
              ],
            )
          ],
        ),
      ),
      actions: [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: TextButton(
            onPressed: () {
              app.install(alwaysTrustSigner: trustedSignerNotifier.value);
              // NOTE: can't use context.pop()
              Navigator.of(context).pop();
            },
            child: Text(
              '${trustedSignerNotifier.value ? 'Always trust' : 'Trust'} ${app.signer.value!.name} and install app',
              style: TextStyle(fontWeight: FontWeight.bold),
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
