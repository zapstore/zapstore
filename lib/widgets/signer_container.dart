import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/author_container.dart';

class SignerContainer extends ConsumerWidget {
  const SignerContainer({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settingsState = ref.settings.watchOne('_', remote: false);

    if (!app.signer.isPresent) {
      return Container();
    }

    return GestureDetector(
      onTap: () async {
        if (app.isSelfSigned) {
          context.push('/developer', extra: app.signer.value);
        } else {
          final url = Uri.parse('https://npub.world/${app.signer.value!.npub}');
          launchUrl(url);
        }
      },
      child: Row(
        children: [
          AuthorContainer(
            user: app.signer.value!,
            beforeText: 'Signed by',
            oneLine: false,
            afterText: settingsState.hasModel &&
                    settingsState.model!.trustedUsers
                        .contains(app.signer.value!)
                ? '(trusted)'
                : null,
          ),
        ],
      ),
    );
  }
}
