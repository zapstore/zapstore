import 'package:flutter/material.dart';
import 'package:zapstore/theme.dart';

extension ContextX on BuildContext {
  ThemeData get theme => Theme.of(this);

  void showInfo(String title, {String? description, IconData? icon}) {
    _showCustomToast(
      context: this,
      title: title,
      description: description,
      icon: icon ?? Icons.info_outline_rounded,
      type: _ToastType.info,
    );
  }

  void showError(
    String title, {
    String? description,
    IconData? icon,
    List<(String, Future<void> Function())> actions = const [],
  }) {
    _showCustomToast(
      context: this,
      title: title,
      description: description,
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
  required IconData icon,
  required _ToastType type,
  List<(String, Future<void> Function())> actions = const [],
}) {
  // Dismiss existing toast
  _currentToast?.remove();
  _currentToast = null;

  final overlay = Overlay.of(context);

  late OverlayEntry entry;
  entry = OverlayEntry(
    builder: (context) => _ToastOverlay(
      title: title,
      description: description,
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

class _ToastOverlay extends StatefulWidget {
  final String title;
  final String? description;
  final IconData icon;
  final _ToastType type;
  final List<(String, Future<void> Function())> actions;
  final VoidCallback onDismiss;

  const _ToastOverlay({
    required this.title,
    this.description,
    required this.icon,
    required this.type,
    required this.actions,
    required this.onDismiss,
  });

  @override
  State<_ToastOverlay> createState() => _ToastOverlayState();
}

class _ToastOverlayState extends State<_ToastOverlay>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _fadeAnimation;
  late Animation<Offset> _slideAnimation;
  bool _isHovered = false;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      duration: const Duration(milliseconds: 300),
      vsync: this,
    );

    _fadeAnimation = CurvedAnimation(
      parent: _controller,
      curve: Curves.easeOut,
    );

    _slideAnimation = Tween<Offset>(
      begin: const Offset(0, -1),
      end: Offset.zero,
    ).animate(CurvedAnimation(
      parent: _controller,
      curve: Curves.easeOutCubic,
    ));

    _controller.forward();
    _startAutoClose();
  }

  void _startAutoClose() {
    final duration = widget.type == _ToastType.error
        ? const Duration(seconds: 6)
        : const Duration(seconds: 4);

    Future.delayed(duration, () {
      if (mounted && !_isHovered) {
        _dismiss();
      }
    });
  }

  void _dismiss() async {
    await _controller.reverse();
    widget.onDismiss();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final screenHeight = MediaQuery.of(context).size.height;
    final maxHeight = screenHeight * 0.7;
    final topPadding = MediaQuery.of(context).padding.top;

    final isError = widget.type == _ToastType.error;

    // Colors
    final backgroundColor = isError
        ? const Color(0xFF1A1215) // Dark reddish-black
        : const Color(0xFF111827); // Dark blue-black

    final borderColor = isError
        ? const Color(0xFF7F1D1D).withValues(alpha: 0.6) // Dark red border
        : AppColors.darkPrimary.withValues(alpha: 0.4);

    final accentColor = isError
        ? const Color(0xFFEF4444) // Red
        : AppColors.darkPrimary;

    final iconBgColor = isError
        ? const Color(0xFF7F1D1D).withValues(alpha: 0.3)
        : AppColors.darkPrimary.withValues(alpha: 0.15);

    return Positioned(
      top: topPadding + 8,
      left: 12,
      right: 12,
      child: SlideTransition(
        position: _slideAnimation,
        child: FadeTransition(
          opacity: _fadeAnimation,
          child: MouseRegion(
            onEnter: (_) => setState(() => _isHovered = true),
            onExit: (_) {
              setState(() => _isHovered = false);
              _startAutoClose();
            },
            child: GestureDetector(
              onTap: _dismiss,
              onVerticalDragEnd: (details) {
                if (details.primaryVelocity != null &&
                    details.primaryVelocity! < -100) {
                  _dismiss();
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
                                icon: widget.icon,
                                iconBgColor: iconBgColor,
                                accentColor: accentColor,
                                title: widget.title,
                                description: widget.description,
                                actions: widget.actions,
                                onDismiss: _dismiss,
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
      ),
    );
  }
}

class _ToastContent extends StatelessWidget {
  final IconData icon;
  final Color iconBgColor;
  final Color accentColor;
  final String title;
  final String? description;
  final List<(String, Future<void> Function())> actions;
  final VoidCallback onDismiss;

  const _ToastContent({
    required this.icon,
    required this.iconBgColor,
    required this.accentColor,
    required this.title,
    this.description,
    required this.actions,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        // Icon
        Container(
          width: 38,
          height: 38,
          decoration: BoxDecoration(
            color: iconBgColor,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(
            icon,
            color: accentColor,
            size: 20,
          ),
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
              // Description
              if (description != null) ...[
                const SizedBox(height: 6),
                Flexible(
                  child: SingleChildScrollView(
                    child: Text(
                      description!,
                      style: TextStyle(
                        fontFamily: kFontFamily,
                        fontSize: 13,
                        fontWeight: FontWeight.w400,
                        color: Colors.white.withValues(alpha: 0.7),
                        height: 1.45,
                      ),
                    ),
                  ),
                ),
              ],
              // Actions
              if (actions.isNotEmpty) ...[
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: actions
                      .map((action) => _ToastActionButton(
                            label: action.$1,
                            onPressed: () async {
                              await action.$2();
                              onDismiss();
                            },
                            accentColor: accentColor,
                          ))
                      .toList(),
                ),
              ],
            ],
          ),
        ),
        // Close button
        const SizedBox(width: 8),
        GestureDetector(
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
