import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nostr_route.dart';

class ExpandableMarkdown extends HookWidget {
  const ExpandableMarkdown({
    super.key,
    required this.data,
    this.onTapLink,
    this.styleSheet,
  });

  final String data;
  final void Function(String, String?, String?)? onTapLink;
  final MarkdownStyleSheet? styleSheet;

  @override
  Widget build(BuildContext context) {
    final expanded = useState(false);
    const maxHeight = 170.0;

    bool isLikelyLong(String text) {
      final trimmed = text.trim();
      if (trimmed.isEmpty) return false;

      final wordCount = trimmed.split(RegExp(r'\s+')).length;
      final newlineCount = '\n'.allMatches(trimmed).length;
      final charCount = trimmed.length;

      return wordCount > 90 || newlineCount > 6 || charCount > 600;
    }

    final shouldCollapse = !expanded.value && isLikelyLong(data);

    final effectiveTapLink = onTapLink ??
        (String text, String? href, String? title) {
          if (href != null) navigateToContent(context, href);
        };

    final markdown = MarkdownBody(
      data: data,
      onTapLink: effectiveTapLink,
      styleSheet: styleSheet,
    );

    if (!shouldCollapse) {
      return markdown;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: maxHeight),
          child: ShaderMask(
            shaderCallback: (Rect bounds) {
              return const LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Colors.white, Colors.white, Colors.transparent],
                stops: [0.0, 0.8, 1.0],
              ).createShader(bounds);
            },
            blendMode: BlendMode.dstIn,
            child: SingleChildScrollView(
              physics: const NeverScrollableScrollPhysics(),
              child: markdown,
            ),
          ),
        ),
        const SizedBox(height: 8),
        Center(
          child: FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.08),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              minimumSize: Size.zero,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            onPressed: () => expanded.value = true,
            child: Text('Read more', style: context.textTheme.labelSmall),
          ),
        ),
      ],
    );
  }
}
