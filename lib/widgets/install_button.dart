import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:percent_indicator/linear_percent_indicator.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/widgets/install_alert_dialog.dart';
import 'package:zapstore/widgets/spinning_logo.dart';

class InstallButton extends HookConsumerWidget {
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

    useMemoized(() {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (progress is IdleInstallProgress && progress.success == true) {
          context.showInfo('Success',
              description:
                  '${app.name ?? app.identifier} was successfully installed');
        }
      });
    }, [app.id, progress]);

    return GestureDetector(
      onTap: switch (status) {
        AppInstallStatus.downgrade ||
        AppInstallStatus.certificateMismatch =>
          null,
        AppInstallStatus.updated => () {
            packageManager.openApp(app.identifier!);
          },
        _ => switch (progress) {
            IdleInstallProgress() => () {
                if (app.canInstall) {
                  final settings = ref.settings.findOneLocalById('_')!;
                  if (settings.trustedUsers.contains(app.signer.value!)) {
                    app.install().catchError((e) {
                      if (context.mounted) {
                        context.showError(
                            title: 'Could not install', description: e.message);
                      }
                    });
                  } else {
                    showDialog(
                      context: context,
                      builder: (context) => InstallAlertDialog(app: app),
                    );
                  }
                } else if (app.canUpdate) {
                  app.install().catchError((e) {
                    if (context.mounted) {
                      context.showError(
                          title: 'Could not install', description: e.message);
                    }
                  });
                } else {
                  // no action
                }
              },
            ErrorInstallProgress(:final e, :final info, :final actions) => () {
                // show error and reset state to idle
                context.showError(
                    title: e.message, description: info, actions: actions);
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
          IdleInstallProgress() =>
            (!app.canInstall && !app.canUpdate || app.hasCertificateMismatch)
                ? Colors.grey
                : kUpdateColor,
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
          AppInstallStatus.certificateMismatch => Text(
              'Not possible to update',
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
                        style: TextStyle(fontSize: compact ? 10 : 14),
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
              ErrorInstallProgress() => Padding(
                  padding: const EdgeInsets.only(left: 8, right: 8),
                  child: Text(
                    'Error (tap for details)',
                    textAlign: TextAlign.center,
                  ),
                ),
            }
        },
      ),
    );
  }
}

final kUpdateColor = Colors.blue[700]!;
