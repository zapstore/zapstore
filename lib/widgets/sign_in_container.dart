import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:gap/gap.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:purplebase/purplebase.dart' as base;
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/settings.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/navigation/app_initializer.dart';
import 'package:zapstore/screens/settings_screen.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class SignInButton extends ConsumerWidget {
  final bool minimal;
  final String label;
  final bool requireNip55;

  SignInButton({
    super.key,
    this.minimal = false,
    this.label = 'Sign in',
    this.requireNip55 = false,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    var user = ref.watch(signedInUserProvider);
    final signedInWithPubkey =
        user != null && user.settings.value!.signInMethod != SignInMethod.nip55;
    if (requireNip55 && signedInWithPubkey) {
      user = null;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            if (!minimal) RoundedImage(url: user?.avatarUrl, size: 46),
            if (!minimal) Gap(10),
            if (user != null)
              Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(
                        user.nameOrNpub,
                        // softWrap: true,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      Gap(4),
                      Icon(Icons.verified, color: Colors.lightBlue, size: 18),
                    ],
                  ),
                  // if (user.following.isNotEmpty)
                  //   Text('${user.following.length} contacts'),
                ],
              ),
          ],
        ),
        Gap(10),
        ElevatedButton(
          onPressed: () async {
            if (user == null) {
              showDialog(
                context: context,
                builder: (_) => AlertDialog(
                  title: Text(
                    label,
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  content: SignInDialogBox(
                    publicKeyAllowed: !requireNip55,
                  ),
                ),
              );
            } else {
              ref.settings.findOneLocalById('_')!.user.value = null;
            }
          },
          child: Text(user == null ? label : 'Sign out'),
        ),
      ],
    );
  }
}

class SignInDialogBox extends HookConsumerWidget {
  final bool publicKeyAllowed;
  const SignInDialogBox({super.key, this.publicKeyAllowed = true});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = useTextEditingController();
    final isTextFieldEmpty = useState(true);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (amberSigner.isAvailable)
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  AsyncButtonBuilder(
                    loadingWidget: SmallCircularProgressIndicator(),
                    builder: (context, child, callback, buttonState) {
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 2),
                        child: SizedBox(
                          child: ElevatedButton(
                            onPressed: callback,
                            style: ElevatedButton.styleFrom(
                                disabledBackgroundColor: Colors.transparent,
                                backgroundColor: Colors.transparent),
                            child: child,
                          ),
                        ),
                      );
                    },
                    onPressed: () async {
                      if (!amberSigner.isAvailable) {
                        Navigator.of(context).pop();
                      }
                      final signedInNpub = await amberSigner.getPublicKey();
                      if (signedInNpub == null) {
                        if (context.mounted) {
                          Navigator.of(context).pop();
                          context.showError('Could not sign in',
                              description: 'Signer did not respond');
                        }
                        return;
                      }

                      var user = await ref.users.findOne(signedInNpub);

                      // If user was not found on relays, we create a
                      // local user to represent this new npub
                      user ??= User.fromPubkey(signedInNpub.hexKey)
                          .init()
                          .saveLocal();

                      final settings = ref.settings.findOneLocalById('_')!;
                      settings.signInMethod = SignInMethod.nip55;
                      settings.user.value = user;
                      settings.saveLocal();

                      if (context.mounted) {
                        Navigator.of(context).pop(user);
                      }
                    },
                    child: Text('Sign in with Amber'),
                  ),
                ],
              ),
            if (amberSigner.isAvailable && publicKeyAllowed)
              Padding(
                padding: const EdgeInsets.all(10),
                child: Text('or'),
              ),
            if (publicKeyAllowed)
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Input an npub or nostr address\n(read-only):'),
                  TextField(
                    autocorrect: false,
                    controller: controller,
                    onChanged: (value) {
                      isTextFieldEmpty.value = value.isEmpty;
                    },
                  ),
                  Gap(10),
                  AsyncButtonBuilder(
                    disabled: isTextFieldEmpty.value,
                    loadingWidget: SmallCircularProgressIndicator(),
                    onPressed: () async {
                      try {
                        final input = controller.text.trim();
                        if (input.startsWith('nsec')) {
                          controller.clear();
                          throw Exception('Never give away your nsec!');
                        }
                        final user = await ref.users.findOne(input);

                        final settings = ref.settings.findOneLocalById('_')!;
                        settings.signInMethod = SignInMethod.pubkey;
                        settings.user.value = user;
                        settings.saveLocal();

                        if (context.mounted) {
                          Navigator.pop(context);
                        }
                      } on Exception catch (e, stack) {
                        if (context.mounted) {
                          context.showError(e.toString(),
                              description: stack.toString().safeSubstring(200));
                        }
                      }
                    },
                    builder: (context, child, callback, buttonState) {
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 2),
                        child: SizedBox(
                          child: ElevatedButton(
                            onPressed: callback,
                            style: ElevatedButton.styleFrom(
                                disabledBackgroundColor: Colors.transparent,
                                backgroundColor: Colors.transparent),
                            child: child,
                          ),
                        ),
                      );
                    },
                    child: Text('Sign in with public key'),
                  ),
                ],
              ),
          ],
        ),
      ],
    );
  }
}
