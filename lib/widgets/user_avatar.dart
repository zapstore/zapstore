import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class UserAvatar extends HookConsumerWidget {
  const UserAvatar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInUser = ref.watch(signedInUserProvider);
    return RoundedImage(url: signedInUser?.avatarUrl, size: 46);
  }
}
