import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/main.dart';
import 'package:zapstore/utils/system_info.dart';
import 'package:zapstore/widgets/app_drawer.dart';

class DrawerContainer extends StatelessWidget {
  const DrawerContainer({
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    return Drawer(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 24, 16, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: LoginContainer()),
            Consumer(
              builder: (context, ref, _) {
                final state = ref.watch(systemInfoProvider);
                return switch (state) {
                  AsyncData(:final value) => Text(
                      'Version: ${value.zsInfo.versionName} (${value.zsInfo.versionCode}, $kDbVersion)',
                      style: TextStyle(fontSize: 16),
                    ),
                  _ => Container(),
                };
              },
            ),
          ],
        ),
      ),
    );
  }
}
