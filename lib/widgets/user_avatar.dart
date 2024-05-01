import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/session_service.dart';

class UserAvatar extends HookConsumerWidget {
  const UserAvatar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(loggedInUser);
    return CircleAvatar(
      radius: 20,
      // backgroundImage: AssetImage('assets/images/logo.png'),
      backgroundColor: Colors.grey[850],
      foregroundImage: user != null ? NetworkImage(user.avatarUrl!) : null,
    );
  }
}
