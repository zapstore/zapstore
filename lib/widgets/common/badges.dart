import 'package:flutter/material.dart';

/// Generic pill-style badge container used for counts or small status indicators.
class BadgePill extends StatelessWidget {
  const BadgePill({
    super.key,
    required this.child,
    this.color,
    this.padding = const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
    this.constraints = const BoxConstraints(minWidth: 18, minHeight: 18),
    this.borderRadius,
  });

  final Widget child;
  final Color? color;
  final EdgeInsetsGeometry padding;
  final BoxConstraints constraints;
  final BorderRadius? borderRadius;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: padding,
      constraints: constraints,
      decoration: BoxDecoration(
        color: color ?? Colors.red.withValues(alpha: 0.4),
        borderRadius: borderRadius ?? BorderRadius.circular(9),
      ),
      child: Center(child: child),
    );
  }
}

/// Count badge that renders `99+` when count > 99.
class CountBadge extends StatelessWidget {
  const CountBadge({
    super.key,
    required this.count,
    this.color,
    this.textStyle,
  });

  final int count;
  final Color? color;
  final TextStyle? textStyle;

  @override
  Widget build(BuildContext context) {
    final display = count > 99 ? '99+' : count.toString();

    return BadgePill(
      color: color,
      child: Text(
        display,
        style:
            textStyle ??
            const TextStyle(
              color: Colors.white,
              fontSize: 10,
              fontWeight: FontWeight.bold,
            ),
        textAlign: TextAlign.center,
      ),
    );
  }
}
