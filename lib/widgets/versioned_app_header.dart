import 'package:auto_size_text/auto_size_text.dart';
import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/pill_widget.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class VersionedAppHeader extends StatelessWidget {
  const VersionedAppHeader({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisAlignment: MainAxisAlignment.start,
      children: [
        RoundedImage(
          url: app.icons.firstOrNull,
          size: 80,
          radius: 25,
        ),
        Gap(16),
        Expanded(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Gap(6),
              AutoSizeText(
                app.name!,
                minFontSize: 16,
                style: TextStyle(fontSize: 21, fontWeight: FontWeight.bold),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              Gap(10),
              Row(
                children: [
                  if (app.localApp.value?.installedVersion != null)
                    PillWidget(
                      text:
                          TextSpan(text: app.localApp.value!.installedVersion!),
                      color: Colors.grey[800]!,
                      size: 11,
                    ),
                  Icon(Icons.arrow_right),
                  if (app.canUpdate)
                    PillWidget(
                      text: TextSpan(text: app.latestMetadata!.version!),
                      color: Colors.blue[700]!,
                      size: 11,
                    ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}
