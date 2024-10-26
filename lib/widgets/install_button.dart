import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:percent_indicator/linear_percent_indicator.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/widgets/app_drawer.dart';
import 'package:zapstore/widgets/author_container.dart';
import 'package:zapstore/widgets/spinning_logo.dart';
import 'package:zapstore/widgets/wot_container.dart';

class InstallButton extends ConsumerWidget {
  InstallButton({
    super.key,
    required this.app,
    this.compact = false,
  });

  final bool compact;
  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(installationProgressProvider(app.id!));
    final status = app.localApp.value?.status;

    return GestureDetector(
      onTap: switch (status) {
        AppInstallStatus.downgrade => null,
        AppInstallStatus.updated => () {
            packageManager.openApp(app.identifier!);
          },
        _ => switch (progress) {
            IdleInstallProgress() => () {
                if (app.canInstall) {
                  showDialog(
                    context: context,
                    builder: (BuildContext context) {
                      return InstallAlertDialog(app: app);
                    },
                  );
                } else if (app.canUpdate) {
                  app.install();
                } else {
                  context.showError(
                      title: 'Can\'t install',
                      description: 'Release or signer are missing.');
                }
              },
            ErrorInstallProgress(:final e, :final info) => () {
                // show error and reset state to idle
                context.showError(title: e.message, description: info);
                ref.read(installationProgressProvider(app.id!).notifier).state =
                    IdleInstallProgress();
              },
            _ => null,
          }
      },
      child: LinearPercentIndicator(
        lineHeight: compact ? 30 : 42,
        percent: switch (progress) {
          VerifyingHashProgress() => 1,
          DownloadingInstallProgress(:final progress) => progress,
          _ => switch (status) {
              AppInstallStatus.updated => 1,
              _ => 0,
            },
        },
        backgroundColor: switch (progress) {
          ErrorInstallProgress() => Colors.red,
          _ => kUpdateColor,
        },
        progressColor: Colors.blue[800],
        barRadius: Radius.circular(20),
        padding: EdgeInsets.all(0),
        animateFromLastPercent: true,
        center: switch (status) {
          AppInstallStatus.downgrade => Text(
              'Installed version ${app.localApp.value?.installedVersion ?? ''} is higher, can\'t downgrade',
              textAlign: TextAlign.center,
            ),
          AppInstallStatus.updated => Text('Open'),
          _ => switch (progress) {
              IdleInstallProgress() => app.canUpdate
                  ? Padding(
                      padding: const EdgeInsets.only(left: 8, right: 8),
                      child: AutoSizeText(
                        'Update',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                            fontSize: compact ? 10 : 14,
                            fontWeight: FontWeight.bold),
                      ),
                    )
                  : (app.canInstall
                      ? Text('Install')
                      : Center(child: SpinningLogo(size: 40))),
              DownloadingInstallProgress(:final progress) => Text(
                  '${(progress * 100).floor()}%',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
              VerifyingHashProgress() => Text('Verifying file integrity'),
              RequestInstallProgress() =>
                Text('Requesting ${app.canUpdate ? 'update' : 'installation'}'),
              ErrorInstallProgress(:final e) => Padding(
                  padding: const EdgeInsets.only(left: 8, right: 8),
                  child: Text(
                    '${e.message.substringMax(64)} (tap for more)',
                    textAlign: TextAlign.center,
                  ),
                ),
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
                fromNpub: user?.npub ?? kFranzapNpub,
                toNpub: app.signer.value!.npub,
              ),
            if (app.latestMetadata?.urls.isNotEmpty ?? false)
              Padding(
                padding: const EdgeInsets.only(top: 16, bottom: 16),
                child: RichText(
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

final kUpdateColor = Colors.blue[700]!;
