import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/theme.dart';

extension ContextX on BuildContext {
  ThemeData get theme => Theme.of(this);

  void showInfo(
    String title, {
    String? description,
    String? technicalDetails,
    IconData? icon,
    List<(String, Future<void> Function())> actions = const [],
  }) {
    _showCustomToast(
      context: this,
      title: title,
      description: description,
      technicalDetails: technicalDetails,
      icon: icon ?? Icons.info_outline_rounded,
      type: _ToastType.info,
      actions: actions,
    );
  }

  void showError(
    String title, {
    String? description,
    String? technicalDetails,
    IconData? icon,
    List<(String, Future<void> Function())> actions = const [],
  }) {
    _showCustomToast(
      context: this,
      title: title,
      description: description,
      technicalDetails: technicalDetails,
      icon: icon ?? Icons.error_outline_rounded,
      type: _ToastType.error,
      actions: actions,
    );
  }
}

enum _ToastType { info, error }

OverlayEntry? _currentToast;

void _showCustomToast({
  required BuildContext context,
  required String title,
  String? description,
  String? technicalDetails,
  required IconData icon,
  required _ToastType type,
  List<(String, Future<void> Function())> actions = const [],
}) {
  // Dismiss existing toast
  _currentToast?.remove();
  _currentToast = null;

  final overlay =
      rootNavigatorKey.currentState?.overlay ?? Overlay.maybeOf(context);
  if (overlay == null) return;

  late OverlayEntry entry;
  entry = OverlayEntry(
    builder: (context) => _ToastOverlay(
      title: title,
      description: description,
      technicalDetails: technicalDetails,
      icon: icon,
      type: type,
      actions: actions,
      onDismiss: () {
        entry.remove();
        if (_currentToast == entry) _currentToast = null;
      },
    ),
  );

  _currentToast = entry;
  overlay.insert(entry);
}

class _ToastOverlay extends HookWidget {
  final String title;
  final String? description;
  final String? technicalDetails;
  final IconData icon;
  final _ToastType type;
  final List<(String, Future<void> Function())> actions;
  final VoidCallback onDismiss;

  const _ToastOverlay({
    required this.title,
    this.description,
    this.technicalDetails,
    required this.icon,
    required this.type,
    required this.actions,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    final controller = useAnimationController(
      duration: const Duration(milliseconds: 300),
    );
    final fadeAnimation = useMemoized(
      () => CurvedAnimation(parent: controller, curve: Curves.easeOut),
      [controller],
    );
    final isHovered = useState(false);

    // Forward animation on mount
    useEffect(() {
      controller.forward();
      return null;
    }, const []);

    // Auto-close only for toasts WITHOUT actions
    useEffect(() {
      if (actions.isNotEmpty) return null;

      final duration = type == _ToastType.error
          ? const Duration(seconds: 8)
          : const Duration(seconds: 6);

      final timer = Timer(duration, () {
        if (!isHovered.value) {
          controller.reverse().then((_) => onDismiss());
        }
      });

      return timer.cancel;
    }, const []);

    void dismiss() {
      controller.reverse().then((_) => onDismiss());
    }

    final screenHeight = MediaQuery.of(context).size.height;
    final maxHeight = screenHeight * 0.7;
    final topPadding = MediaQuery.of(context).padding.top;

    final isError = type == _ToastType.error;

    // Colors - info: darker light blue, error: red
    final backgroundColor = isError
        ? const Color(0xFFDC2626) // Red
        : const Color(0xFF0284C7); // Darker light blue (sky-600)

    final borderColor = isError
        ? const Color(0xFFB91C1C).withValues(alpha: 0.8) // Darker red border
        : const Color(
            0xFF0369A1,
          ).withValues(alpha: 0.8); // Even darker blue border (sky-700)

    final accentColor = Colors.white;

    final iconBgColor = Colors.white.withValues(alpha: 0.2);

    return Positioned(
      top: topPadding + 8,
      left: 12,
      right: 12,
      child: FadeTransition(
        opacity: fadeAnimation,
        child: MouseRegion(
          onEnter: (_) => isHovered.value = true,
          onExit: (_) {
            isHovered.value = false;
            // Restart auto-close for toasts without actions
            if (actions.isEmpty) {
              Future.delayed(const Duration(seconds: 3), () {
                if (!isHovered.value) dismiss();
              });
            }
          },
          child: GestureDetector(
            onTap: actions.isEmpty ? dismiss : null,
            onVerticalDragEnd: (details) {
              if (details.primaryVelocity != null &&
                  details.primaryVelocity! < -100) {
                dismiss();
              }
            },
            child: ConstrainedBox(
              constraints: BoxConstraints(maxHeight: maxHeight),
              child: Material(
                color: Colors.transparent,
                child: Container(
                  decoration: BoxDecoration(
                    color: backgroundColor,
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: borderColor, width: 1),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.4),
                        blurRadius: 24,
                        offset: const Offset(0, 8),
                        spreadRadius: 0,
                      ),
                      BoxShadow(
                        color: accentColor.withValues(alpha: 0.08),
                        blurRadius: 40,
                        offset: const Offset(0, 4),
                        spreadRadius: 0,
                      ),
                    ],
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(14),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        // Accent line at top
                        Container(
                          height: 3,
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              colors: [
                                accentColor.withValues(alpha: 0.8),
                                accentColor.withValues(alpha: 0.4),
                                accentColor.withValues(alpha: 0.1),
                              ],
                            ),
                          ),
                        ),
                        // Content
                        Flexible(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: _ToastContent(
                              icon: icon,
                              iconBgColor: iconBgColor,
                              accentColor: accentColor,
                              title: title,
                              description: description,
                              technicalDetails: technicalDetails,
                              actions: actions,
                              onDismiss: dismiss,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ToastContent extends HookWidget {
  final IconData icon;
  final Color iconBgColor;
  final Color accentColor;
  final String title;
  final String? description;
  final String? technicalDetails;
  final List<(String, Future<void> Function())> actions;
  final VoidCallback onDismiss;

  const _ToastContent({
    required this.icon,
    required this.iconBgColor,
    required this.accentColor,
    required this.title,
    this.description,
    this.technicalDetails,
    required this.actions,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    final detailsExpanded = useState(false);

    final hasDescription = description != null && description!.isNotEmpty;
    final hasTechnicalDetails =
        technicalDetails != null && technicalDetails!.isNotEmpty;
    final hasActions = actions.isNotEmpty;
    final hasExtraContent = hasDescription || hasTechnicalDetails || hasActions;

    // Use Stack to position X at top-right always
    return Stack(
      children: [
        // Main content with padding for X button
        Padding(
          padding: const EdgeInsets.only(right: 36), // Space for X button
          child: Row(
            // Center align when no description, top align when there's extra content
            crossAxisAlignment: hasExtraContent
                ? CrossAxisAlignment.start
                : CrossAxisAlignment.center,
            children: [
              // Icon
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: iconBgColor,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: accentColor, size: 20),
              ),
              const SizedBox(width: 14),
              // Text content
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Title
                    Text(
                      title,
                      style: const TextStyle(
                        fontFamily: kFontFamily,
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                        height: 1.35,
                      ),
                    ),
                    // Inline description (always visible)
                    if (hasDescription) ...[
                      const SizedBox(height: 4),
                      Text(
                        description!,
                        style: TextStyle(
                          fontFamily: kFontFamily,
                          fontSize: 13,
                          fontWeight: FontWeight.w400,
                          color: Colors.white.withValues(alpha: 0.85),
                          height: 1.35,
                        ),
                      ),
                    ],
                    // Expandable technical details
                    if (hasTechnicalDetails) ...[
                      const SizedBox(height: 8),
                      GestureDetector(
                        onTap: () {
                          detailsExpanded.value = !detailsExpanded.value;
                        },
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              detailsExpanded.value
                                  ? Icons.expand_less
                                  : Icons.expand_more,
                              size: 16,
                              color: Colors.white.withValues(alpha: 0.7),
                            ),
                            const SizedBox(width: 4),
                            Text(
                              'Technical details',
                              style: TextStyle(
                                fontFamily: kFontFamily,
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                                color: Colors.white.withValues(alpha: 0.7),
                              ),
                            ),
                          ],
                        ),
                      ),
                      // Expanded technical details
                      if (detailsExpanded.value) ...[
                        const SizedBox(height: 6),
                        Container(
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.2),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: SelectableText(
                            technicalDetails!,
                            style: TextStyle(
                              fontFamily: 'monospace',
                              fontSize: 11,
                              fontWeight: FontWeight.w400,
                              color: Colors.white.withValues(alpha: 0.8),
                              height: 1.4,
                            ),
                          ),
                        ),
                      ],
                    ],
                    // Actions
                    if (hasActions) ...[
                      const SizedBox(height: 12),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: actions
                            .map(
                              (action) => _ToastActionButton(
                                label: action.$1,
                                onPressed: () {
                                  onDismiss();
                                  action.$2();
                                },
                                accentColor: accentColor,
                              ),
                            )
                            .toList(),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
        // Close button - always top-right
        Positioned(
          top: 0,
          right: 0,
          child: GestureDetector(
            onTap: onDismiss,
            child: Container(
              width: 28,
              height: 28,
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.06),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Icon(
                Icons.close_rounded,
                size: 16,
                color: Colors.white.withValues(alpha: 0.5),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ToastActionButton extends StatelessWidget {
  final String label;
  final VoidCallback onPressed;
  final Color accentColor;

  const _ToastActionButton({
    required this.label,
    required this.onPressed,
    required this.accentColor,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onPressed,
        borderRadius: BorderRadius.circular(6),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: accentColor.withValues(alpha: 0.15),
            borderRadius: BorderRadius.circular(6),
            border: Border.all(
              color: accentColor.withValues(alpha: 0.3),
              width: 1,
            ),
          ),
          child: Text(
            label,
            style: TextStyle(
              fontFamily: kFontFamily,
              fontSize: 13,
              fontWeight: FontWeight.w500,
              color: accentColor,
            ),
          ),
        ),
      ),
    );
  }
}
