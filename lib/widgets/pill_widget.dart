import 'package:flutter/material.dart';

class PillWidget extends StatelessWidget {
  final InlineSpan text;
  final Color color;
  final double size;
  const PillWidget(
      {super.key,
      required this.text,
      this.color = Colors.blue,
      this.size = 13});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.symmetric(horizontal: size, vertical: 6),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(size * 1.1),
      ),
      child: RichText(
        text: TextSpan(
          style: TextStyle(
            color: Colors.white,
            fontSize: size * 1.2,
            fontWeight: FontWeight.bold,
            fontFamily: 'Inter',
          ),
          children: [text],
        ),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        textAlign: TextAlign.center,
      ),
    );
  }
}
