import 'package:auto_size_text/auto_size_text.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:percent_indicator/linear_percent_indicator.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/models/local_app.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/widgets/install_alert_dialog.dart';
import 'package:zapstore/widgets/spinning_logo.dart';

class ZapButton extends HookConsumerWidget {
  ZapButton({
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
    final nwcSecret = ref.watch(nwcSecretProvider);

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
      onTap: () {
        User? developer = app.developer.value;
        if (developer!=null) {
          // TODO get dev lud16 and get invoice from it, then pay invoice.
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
        backgroundColor: nwcSecret!=null && nwcSecret!=''? kUpdateColor : Colors.grey,
        progressColor: Colors.blue[800],
        barRadius: Radius.circular(20),
        padding: EdgeInsets.all(0),
        animateFromLastPercent: true,
        center: Text('Zap ⚡'),
      ),
    );
  }
}

final kUpdateColor = Colors.blue[700]!;
