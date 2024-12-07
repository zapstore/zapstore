import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/main.data.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/author_container.dart';

class SignerAndDeveloperRow extends ConsumerWidget {
  const SignerAndDeveloperRow({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settingsState = ref.settings.watchOne('_', remote: false);

    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        if (app.developer.isPresent)
          GestureDetector(
            onTap: () async {
              final url =
                  Uri.parse('https://njump.me/${app.developer.value!.npub}');
              launchUrl(url);
            },
            child: AuthorContainer(
                user: app.developer.value!,
                beforeText: 'Developed by',
                oneLine: false),
          ),
        if (app.signer.isPresent)
          GestureDetector(
            onTap: () async {
              final url =
                  Uri.parse('https://njump.me/${app.signer.value!.npub}');
              launchUrl(url);
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
                      : '',
                ),
              ],
            ),
          ),
      ],
    );
  }
}
