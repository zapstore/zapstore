import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// A widget that wraps its child and enables full-screen swipe-to-go-back gesture.
/// Swipe from anywhere on the screen to navigate back.
/// Supports RTL locales and avoids conflicts with horizontal scrollables.
class SwipeBackWrapper extends StatefulWidget {
  const SwipeBackWrapper({
    super.key,
    required this.child,
    this.swipeThreshold = 100.0,
    this.velocityThreshold = 300.0,
    this.edgeWidth = double.infinity,
  });

  final Widget child;

  /// Minimum horizontal distance to trigger back navigation
  final double swipeThreshold;

  /// Minimum velocity to trigger back navigation
  final double velocityThreshold;

  /// Width of the edge zone where swipe can start (to avoid conflicts with
  /// horizontal scrollables like carousels). Set to double.infinity to allow
  /// swipe from anywhere.
  final double edgeWidth;

  @override
  State<SwipeBackWrapper> createState() => _SwipeBackWrapperState();
}

class _SwipeBackWrapperState extends State<SwipeBackWrapper> {
  double _startX = 0;
  double _currentX = 0;
  bool _isSwiping = false;
  bool _isValidSwipeStart = false;

  void _resetState() {
    _isSwiping = false;
    _isValidSwipeStart = false;
    _startX = 0;
    _currentX = 0;
  }

  void _onHorizontalDragStart(DragStartDetails details) {
    final screenWidth = MediaQuery.of(context).size.width;
    final isRtl = Directionality.of(context) == TextDirection.rtl;
    final startX = details.globalPosition.dx;

    // In LTR: swipe starts from left edge
    // In RTL: swipe starts from right edge
    final isFromEdge = isRtl
        ? startX > screenWidth - widget.edgeWidth
        : startX < widget.edgeWidth;

    _startX = startX;
    _currentX = startX;
    _isSwiping = true;
    _isValidSwipeStart = isFromEdge || widget.edgeWidth == double.infinity;
  }

  void _onHorizontalDragUpdate(DragUpdateDetails details) {
    if (!_isSwiping || !_isValidSwipeStart) return;
    _currentX = details.globalPosition.dx;
  }

  void _onHorizontalDragEnd(DragEndDetails details) {
    if (!_isSwiping || !_isValidSwipeStart) {
      _resetState();
      return;
    }

    final isRtl = Directionality.of(context) == TextDirection.rtl;
    final velocity = details.primaryVelocity ?? 0;

    // In LTR: positive distance (left-to-right) and positive velocity
    // In RTL: negative distance (right-to-left) and negative velocity
    final distance = _currentX - _startX;
    final effectiveDistance = isRtl ? -distance : distance;
    final effectiveVelocity = isRtl ? -velocity : velocity;

    // Check if swipe was in the correct direction with sufficient distance or velocity
    if (effectiveDistance > widget.swipeThreshold ||
        effectiveVelocity > widget.velocityThreshold) {
      if (context.canPop()) {
        context.pop();
      }
    }

    _resetState();
  }

  void _onHorizontalDragCancel() {
    _resetState();
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onHorizontalDragStart: _onHorizontalDragStart,
      onHorizontalDragUpdate: _onHorizontalDragUpdate,
      onHorizontalDragEnd: _onHorizontalDragEnd,
      onHorizontalDragCancel: _onHorizontalDragCancel,
      child: widget.child,
    );
  }
}
