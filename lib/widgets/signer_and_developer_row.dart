import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/author_container.dart';

class SignerAndDeveloperRow extends StatelessWidget {
  const SignerAndDeveloperRow({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context) {
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
                user: app.developer.value!, text: 'Built by', oneLine: false),
          ),
        if (app.signer.isPresent)
          GestureDetector(
            onTap: () async {
              final url =
                  Uri.parse('https://njump.me/${app.signer.value!.npub}');
              launchUrl(url);
            },
            child: AuthorContainer(
                user: app.signer.value!, text: 'Signed by', oneLine: false),
          ),
      ],
    );
  }
}
