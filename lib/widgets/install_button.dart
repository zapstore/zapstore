import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:percent_indicator/linear_percent_indicator.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/widgets/app_drawer.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/wot_container.dart';

class InstallButton extends ConsumerWidget {
  InstallButton({
    super.key,
    required this.app,
    this.compact = false,
    this.disabled = false,
  });

  final bool disabled;
  final bool compact;
  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(installationProgressProvider(app.identifier));

    return GestureDetector(
      onTap: switch (app.status) {
        AppInstallStatus.differentArchitecture => null,
        AppInstallStatus.downgrade => null,
        AppInstallStatus.updated => () {
            packageManager.openApp(app.id!.toString());
          },
        _ => switch (progress) {
            IdleInstallProgress() => () {
                // show trust dialog only if first install
                if (disabled) {
                  context.showError('Missing signer');
                }
                if (app.canInstall) {
                  showDialog(
                    context: context,
                    builder: (BuildContext context) {
                      return InstallAlertDialog(app: app);
                    },
                  );
                } else if (app.canUpdate) {
                  app.install();
                }
              },
            ErrorInstallProgress(:final e) => () {
                // show error and reset state to idle
                context.showError((e as dynamic).message);
                ref
                    .read(installationProgressProvider(app.id!.toString())
                        .notifier)
                    .state = IdleInstallProgress();
              },
            _ => null,
          }
      },
      child: LinearPercentIndicator(
        lineHeight: 40,
        percent: switch (progress) {
          VerifyingHashProgress() => 1,
          DownloadingInstallProgress(:final progress) => progress,
          _ => switch (app.status) {
              AppInstallStatus.updated => 1,
              _ => 0,
            },
        },
        backgroundColor: switch (progress) {
          ErrorInstallProgress() => Colors.red,
          _ => Colors.blue[700],
        },
        progressColor: Colors.blue[800],
        barRadius: Radius.circular(20),
        padding: EdgeInsets.all(0),
        animateFromLastPercent: true,
        center: switch (app.status) {
          AppInstallStatus.loading =>
            SizedBox(width: 14, height: 14, child: CircularProgressIndicator()),
          AppInstallStatus.differentArchitecture => Text(
              'Sorry, release does not support your device',
              textAlign: TextAlign.center,
            ),
          AppInstallStatus.downgrade => Text(
              'Installed version ${app.installedVersion ?? ''} is higher, can\'t downgrade',
              textAlign: TextAlign.center,
            ),
          AppInstallStatus.updated => Text('Open'),
          _ => switch (progress) {
              IdleInstallProgress() => app.canUpdate
                  ? AutoSizeText(
                      'Update${compact ? '' : ' to ${app.latestMetadata!.version!}'}',
                      maxLines: 1,
                    )
                  : Text('Install'),
              DownloadingInstallProgress(:final progress) => Text(
                  '${(progress * 100).floor()}%',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
              VerifyingHashProgress() => compact
                  ? SizedBox(
                      width: 14, height: 14, child: CircularProgressIndicator())
                  : Text('Verifying file integrity'),
              HashVerifiedInstallProgress() => compact
                  ? SizedBox(
                      width: 14, height: 14, child: CircularProgressIndicator())
                  : Text(
                      'Hash verified, requesting ${app.canUpdate ? 'update' : 'installation'}'),
              ErrorInstallProgress() => compact
                  ? SizedBox(width: 14, height: 14, child: Icon(Icons.error))
                  : Text('Error, tap to see message'),
            }
        },
      ),
    );
  }
}

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
            // SignerAndDeveloperRow(app: app),
            if (app.signer.value != null)
              AuthorContainer(
                  user: app.signer.value!, text: 'Signed by', oneLine: true),
            Gap(20),
            if (app.signer.value != null)
              WebOfTrustContainer(
                  user: user, npub: user?.npub, npub2: app.signer.value!.npub),
            if (user != null)
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Gap(20),
                  Text('The app will be downloaded from:\n'),
                  Text(
                    app.latestMetadata!.urls.firstOrNull ??
                        'https://cdn.zap.store/${app.latestMetadata!.hash}',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                ],
              ),
          ],
        ),
      ),
      actions: [
        if (user == null)
          LoginContainer(
            minimal: true,
            labelText: 'Log in to view your own web of trust',
          ),
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
                    'I trust the signer, install anyway',
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
