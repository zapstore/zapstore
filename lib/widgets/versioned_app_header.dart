import 'package:auto_size_text/auto_size_text.dart';
import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/install_button.dart';
import 'package:zapstore/widgets/pill_widget.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class VersionedAppHeader extends StatelessWidget {
  const VersionedAppHeader({
    super.key,
    required this.app,
    this.showUpdate = false,
  });

  final bool showUpdate;
  final App app;

  @override
  Widget build(BuildContext context) {
    final isUpdate = app.canUpdate && showUpdate;
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
              AutoSizeText(
                app.name!,
                minFontSize: 16,
                style: TextStyle(fontSize: 21, fontWeight: FontWeight.bold),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              Gap(14),
              Wrap(
                children: [
                  if (isUpdate)
                    PillWidget(
                        text: app.installedVersion!, color: Colors.grey[800]!),
                  if (isUpdate) Icon(Icons.arrow_right),
                  if (app.latestMetadata != null)
                    PillWidget(
                      text: app.latestMetadata!.version!,
                      color: Colors.grey[800]!,
                    ),
                ],
              ),
            ],
          ),
        ),
        if (isUpdate)
          SizedBox(
            width: 90,
            height: 40,
            child: InstallButton(
              app: app,
              compact: true,
            ),
          ),
      ],
    );
  }
}
