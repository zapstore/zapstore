import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';

/// An animated spinning logo widget used for loading states
/// Continuously rotates the Zapstore logo image
class SpinningLogo extends HookWidget {
  final double size;
  const SpinningLogo({super.key, this.size = 200});

  @override
  Widget build(BuildContext context) {
    final controller = useAnimationController(
      duration: const Duration(seconds: 2),
    )..repeat();

    return Center(
      child: AnimatedBuilder(
        animation: controller,
        builder: (context, child) {
          return Transform.rotate(
            angle: controller.value * 2.0 * 3.14159,
            child: child,
          );
        },
        child: Image.asset(
          'assets/images/logo-fg.png',
          height: size,
          width: size,
        ),
      ),
    );
  }
}
