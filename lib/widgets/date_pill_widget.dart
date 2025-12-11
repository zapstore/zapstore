import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:zapstore/utils/extensions.dart';

/// Date pill widget showing a formatted date in pill format
class DatePillWidget extends StatelessWidget {
  final DateTime date;

  const DatePillWidget({super.key, required this.date});

  @override
  Widget build(BuildContext context) {
    final formattedDate = DateFormat('MMM d, y').format(date);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.grey.shade300,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        formattedDate,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: context.textTheme.labelMedium?.copyWith(
          fontWeight: FontWeight.bold,
          color: Colors.grey.shade700,
        ),
      ),
    );
  }
}
