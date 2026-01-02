import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// A widget that wraps its child and enables full-screen swipe-to-go-back gesture.
/// Swipe from anywhere on the screen (left to right) to navigate back.
class SwipeBackWrapper extends StatefulWidget {
  const SwipeBackWrapper({
    super.key,
    required this.child,
    this.swipeThreshold = 100.0,
    this.velocityThreshold = 300.0,
  });

  final Widget child;

  /// Minimum horizontal distance to trigger back navigation
  final double swipeThreshold;

  /// Minimum velocity to trigger back navigation
  final double velocityThreshold;

  @override
  State<SwipeBackWrapper> createState() => _SwipeBackWrapperState();
}

class _SwipeBackWrapperState extends State<SwipeBackWrapper> {
  double _startX = 0;
  double _currentX = 0;
  bool _isSwiping = false;

  void _onHorizontalDragStart(DragStartDetails details) {
    _startX = details.globalPosition.dx;
    _currentX = _startX;
    _isSwiping = true;
  }

  void _onHorizontalDragUpdate(DragUpdateDetails details) {
    if (!_isSwiping) return;
    _currentX = details.globalPosition.dx;
  }

  void _onHorizontalDragEnd(DragEndDetails details) {
    if (!_isSwiping) return;

    final distance = _currentX - _startX;
    final velocity = details.primaryVelocity ?? 0;

    // Check if swipe was left-to-right with sufficient distance or velocity
    if (distance > widget.swipeThreshold || velocity > widget.velocityThreshold) {
      if (context.canPop()) {
        context.pop();
      }
    }

    _isSwiping = false;
    _startX = 0;
    _currentX = 0;
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onHorizontalDragStart: _onHorizontalDragStart,
      onHorizontalDragUpdate: _onHorizontalDragUpdate,
      onHorizontalDragEnd: _onHorizontalDragEnd,
      child: widget.child,
    );
  }
}
