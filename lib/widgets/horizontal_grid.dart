import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/app_card.dart';

class HorizontalGrid extends StatelessWidget {
  final List<App> apps;

  HorizontalGrid({super.key, required this.apps});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 230,
      child: GridView.builder(
        scrollDirection: Axis.horizontal,
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          childAspectRatio: 1.15,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
        ),
        itemCount: apps.isEmpty ? 8 : apps.length,
        itemBuilder: (context, i) => TinyAppCard(app: apps.elementAtOrNull(i)),
      ),
    );
  }
}
