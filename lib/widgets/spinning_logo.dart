import 'package:flutter/material.dart';

class SpinningLogo extends StatefulWidget {
  const SpinningLogo({super.key});

  @override
  SpinningLogoState createState() => SpinningLogoState();
}

class SpinningLogoState extends State<SpinningLogo>
    with TickerProviderStateMixin {
  late AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: Duration(seconds: 2),
    )..repeat(); // Repeat the animation indefinitely
  }

  @override
  void dispose() {
    _controller.dispose(); // Dispose of the controller when done
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Center(
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          return Transform.rotate(
            angle:
                _controller.value * 2.0 * 3.14159, // Full rotation in radians
            child: child,
          );
        },
        child: Image.asset(
          'assets/images/logo-fg.png',
          height: 200,
          width: 200,
        ), // Adjust size as needed
      ),
    );
  }
}