import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';

/// An animated breathing logo widget used for loading states
/// Smoothly scales the Zapstore logo in a "breathe in, breathe out" motion
class BreathingLogo extends HookWidget {
  final double size;
  const BreathingLogo({super.key, this.size = 200});

  @override
  Widget build(BuildContext context) {
    final controller = useAnimationController(
      duration: const Duration(milliseconds: 1500),
    )..repeat(reverse: true);

    final scaleAnimation = useMemoized(
      () => Tween<double>(begin: 0.85, end: 1.0).animate(
        CurvedAnimation(
          parent: controller,
          curve: Curves.easeInOut,
        ),
      ),
      [controller],
    );

    return Center(
      child: AnimatedBuilder(
        animation: scaleAnimation,
        builder: (context, child) {
          return Transform.scale(
            scale: scaleAnimation.value,
            child: child,
          );
        },
        child: Image.asset(
          'assets/images/logo.png',
          height: size,
          width: size,
        ),
      ),
    );
  }
}

