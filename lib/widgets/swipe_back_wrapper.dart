import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// A widget that enables swipe-to-go-back gesture navigation.
///
/// Wraps a child widget and detects horizontal swipe gestures to trigger
/// back navigation via [GoRouter]. Supports both LTR and RTL text directions,
/// automatically adjusting the swipe direction based on locale.
///
/// ## Usage
///
/// ```dart
/// SwipeBackWrapper(
///   child: MyDetailScreen(),
/// )
/// ```
///
/// ## RTL Support
///
/// In LTR locales, swipe left-to-right to go back.
/// In RTL locales, swipe right-to-left to go back.
///
/// ## Edge Zone Configuration
///
/// By default, swipes can start from anywhere on screen ([edgeWidth] = infinity).
/// To limit swipes to start from the screen edge (useful when child contains
/// horizontal scrollables like carousels):
///
/// ```dart
/// SwipeBackWrapper(
///   edgeWidth: 40.0, // Only detect swipes starting within 40px of edge
///   child: MyScreenWithCarousel(),
/// )
/// ```
///
/// ## Limitations
///
/// When [edgeWidth] is finite, this widget still participates in the gesture
/// arena for the entire screen. For true edge-only gesture handling that
/// doesn't interfere with horizontal scrollables, consider using a custom
/// [HorizontalDragGestureRecognizer] with [RawGestureDetector].
///
/// See also:
///  * [CupertinoPageTransitionsBuilder], which provides native iOS edge swipe
///  * [GoRouter], the navigation library used for back navigation
class SwipeBackWrapper extends StatefulWidget {
  /// Creates a swipe-to-go-back wrapper.
  ///
  /// The [child] argument must not be null.
  /// The [edgeWidth] must be positive (greater than 0).
  /// The [swipeThreshold] and [velocityThreshold] must be non-negative.
  const SwipeBackWrapper({
    super.key,
    required this.child,
    this.swipeThreshold = 100.0,
    this.velocityThreshold = 300.0,
    this.edgeWidth = double.infinity,
  })  : assert(edgeWidth > 0, 'edgeWidth must be positive'),
        assert(swipeThreshold >= 0, 'swipeThreshold must be non-negative'),
        assert(velocityThreshold >= 0, 'velocityThreshold must be non-negative');

  /// The widget below this widget in the tree.
  ///
  /// This widget will receive all touch events except horizontal swipes
  /// that trigger back navigation.
  final Widget child;

  /// Minimum horizontal distance in logical pixels to trigger back navigation.
  ///
  /// If the user swipes at least this distance in the back direction,
  /// navigation will be triggered regardless of velocity.
  ///
  /// Defaults to 100.0 logical pixels.
  final double swipeThreshold;

  /// Minimum swipe velocity in logical pixels per second to trigger back navigation.
  ///
  /// If the user swipes with at least this velocity in the back direction,
  /// navigation will be triggered regardless of distance.
  ///
  /// Defaults to 300.0 logical pixels per second.
  final double velocityThreshold;

  /// Width of the edge zone where swipe gestures are recognized.
  ///
  /// In LTR locales, this is measured from the left edge.
  /// In RTL locales, this is measured from the right edge.
  ///
  /// Set to [double.infinity] (the default) to allow swipes to start from
  /// anywhere on screen.
  ///
  /// Set to a finite value (e.g., 40.0) to only recognize swipes that start
  /// within that distance from the edge. This can help avoid conflicts with
  /// horizontal scrollable widgets in the child tree.
  ///
  /// Note: Even with a finite value, this widget still participates in the
  /// gesture arena for the entire screen surface.
  final double edgeWidth;

  @override
  State<SwipeBackWrapper> createState() => _SwipeBackWrapperState();
}

/// State for [SwipeBackWrapper].
///
/// Tracks the horizontal drag gesture and determines when to trigger
/// back navigation based on distance and velocity thresholds.
class _SwipeBackWrapperState extends State<SwipeBackWrapper> {
  /// The x-coordinate where the current drag started.
  double _startX = 0;

  /// The current x-coordinate of the drag.
  double _currentX = 0;

  /// Whether a drag gesture is currently in progress.
  bool _isSwiping = false;

  /// Whether the drag started within the valid edge zone.
  ///
  /// This is only relevant when [SwipeBackWrapper.edgeWidth] is finite.
  bool _isValidSwipeStart = false;

  /// Resets all drag tracking state to initial values.
  ///
  /// Called when a drag ends, is canceled, or should be ignored.
  void _resetState() {
    _isSwiping = false;
    _isValidSwipeStart = false;
    _startX = 0;
    _currentX = 0;
  }

  /// Handles the start of a horizontal drag gesture.
  ///
  /// Records the starting position and determines if the drag started
  /// within the valid edge zone based on [SwipeBackWrapper.edgeWidth]
  /// and the current text direction.
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

  /// Handles updates during a horizontal drag gesture.
  ///
  /// Updates the current position if this is a valid swipe in progress.
  void _onHorizontalDragUpdate(DragUpdateDetails details) {
    if (!_isSwiping || !_isValidSwipeStart) return;
    _currentX = details.globalPosition.dx;
  }

  /// Handles the end of a horizontal drag gesture.
  ///
  /// Evaluates if the swipe meets the distance or velocity threshold
  /// and triggers back navigation if so. The direction is adjusted
  /// based on the current text direction (LTR vs RTL).
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

  /// Handles cancellation of a horizontal drag gesture.
  ///
  /// Resets state when the system cancels the gesture (e.g., when
  /// another gesture wins the arena or the app loses focus).
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
