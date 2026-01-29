import 'dart:ui';
import 'package:flutter/material.dart';

/// Helper function to show a dialog with consistent blur background
Future<T?> showBaseDialog<T>({
  required BuildContext context,
  required Widget dialog,
  bool barrierDismissible = true,
}) {
  return showDialog<T>(
    context: context,
    barrierColor: Colors.black.withValues(alpha: 0.25),
    barrierDismissible: barrierDismissible,
    builder: (context) => dialog,
  );
}

class BaseDialog extends StatelessWidget {
  const BaseDialog({
    super.key,
    required this.title,
    required this.content,
    required this.actions,
    this.titleIcon,
    this.titleIconColor,
    this.maxWidth = 560,
    this.applyFontSizeFactor = false,
    this.fontSizeFactor = 1.16,
  });

  final Widget title;
  final Widget content;
  final List<Widget> actions;
  final Widget? titleIcon;
  final Color? titleIconColor;
  final double maxWidth;
  final bool applyFontSizeFactor;
  final double fontSizeFactor;

  @override
  Widget build(BuildContext context) {
    Widget dialogContent = AlertDialog(
      elevation: 10,
      insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
      title: titleIcon != null
          ? Row(
              children: [
                DefaultTextStyle(
                  style: TextStyle(color: titleIconColor, fontSize: 16),
                  child: titleIcon!,
                ),
                const SizedBox(width: 8),
                Expanded(child: title),
              ],
            )
          : title,
      content: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxWidth),
        child: SingleChildScrollView(child: content),
      ),
      actions: actions,
    );

    if (applyFontSizeFactor) {
      dialogContent = Theme(
        data: Theme.of(context).copyWith(
          textTheme: Theme.of(
            context,
          ).textTheme.apply(fontSizeFactor: fontSizeFactor),
        ),
        child: dialogContent,
      );
    }

    // Wrap with blur effect
    return ClipRect(
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 8, sigmaY: 8),
        child: dialogContent,
      ),
    );
  }
}

class BaseDialogTitle extends StatelessWidget {
  const BaseDialogTitle(this.text, {super.key, this.style});

  final String text;
  final TextStyle? style;

  @override
  Widget build(BuildContext context) {
    return FittedBox(
      fit: BoxFit.scaleDown,
      alignment: Alignment.centerLeft,
      child: Text(
        text,
        style: style ?? const TextStyle(fontWeight: FontWeight.bold),
      ),
    );
  }
}

class BaseDialogContent extends StatelessWidget {
  const BaseDialogContent({
    super.key,
    required this.children,
    this.mainAxisSize = MainAxisSize.min,
    this.crossAxisAlignment = CrossAxisAlignment.start,
    this.padding,
  });

  final List<Widget> children;
  final MainAxisSize mainAxisSize;
  final CrossAxisAlignment crossAxisAlignment;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    Widget content = Column(
      mainAxisSize: mainAxisSize,
      crossAxisAlignment: crossAxisAlignment,
      children: children,
    );

    if (padding != null) {
      content = Padding(padding: padding!, child: content);
    }

    return content;
  }
}

class BaseDialogAction extends StatelessWidget {
  const BaseDialogAction({
    super.key,
    required this.onPressed,
    required this.child,
    this.isPrimary = false,
    this.padding,
  });

  final VoidCallback? onPressed;
  final Widget child;
  final bool isPrimary;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    Widget button = TextButton(onPressed: onPressed, child: child);

    if (padding != null) {
      button = Padding(padding: padding!, child: button);
    }

    return button;
  }
}
