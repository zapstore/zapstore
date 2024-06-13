import 'dart:async';
import 'dart:io';

import 'package:cli_util/cli_logging.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/models/user.dart';

/// zap.store command line executable `zs`
/// fvm dart compile exe cli/zs.dart
Future<void> main(List<String> args) async {
  final logger = Logger.standard();
  late final Directory dir;
  late final ProviderContainer container;

  try {
    dir = Directory.systemTemp.createTempSync();

    container = ProviderContainer(
      overrides: [
        localStorageProvider.overrideWithValue(
          LocalStorage(
            baseDirFn: () => dir.path,
            clear: LocalStorageClearStrategy.always,
          ),
        ),
      ],
    );

    logger
        .stdout('Installing ${logger.ansi.emphasized('bitcoin-core 27.0')}\n');
    logger.stdout('Please input your npub:');
    String? npub = stdin.readLineSync();

    logger.stdout('');

    await container
        .read(initializeFlutterData({'users': usersAdapterProvider}).future);
    container
        .read(relayMessageNotifierProvider.notifier)
        .initialize(['wss://relay.zap.store', 'wss://relay.nostr.band']);

    final progress = logger.progress('Calculating web of trust');
    final out = await container
        .read(usersAdapterProvider)
        .userAdapter
        .getTrusted(npub!,
            'npub10r8xl2njyepcw2zwv3a6dyufj4e4ajx86hz6v4ehu4gnpupxxp7stjt2p8');
    progress.finish(showTiming: true);
    logger.stdout(
        '${out.map((u) => u.nameOrNpub).join(', ')} and others follow this signer\n');

    logger.stdout('(${out.map((u) => u.npub).join(', ')}, ...)\n');

    logger.stdout(
        logger.ansi.emphasized('Are you sure you want to continue? [y/N]'));

    String? confirm = stdin.readLineSync();
    if (['Y', 'y', 'yes'].contains(confirm)) {
      final p2 = logger.progress(
          'Installing ${logger.ansi.emphasized('bitcoin-core 27.0')}');
      await Future.delayed(Duration(seconds: 6));
      p2.finish(showTiming: true);
      logger.stdout('Package installed.');
    } else {
      logger.stderr('Abort');
    }
  } finally {
    await container.read(localStorageProvider).destroy();
    exit(0);
  }
}
