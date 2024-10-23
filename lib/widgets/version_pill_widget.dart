import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:zapstore/models/app.dart';
import 'package:zapstore/widgets/install_button.dart';
import 'package:zapstore/widgets/pill_widget.dart';

class VersionPillWidget extends StatelessWidget {
  const VersionPillWidget({
    super.key,
    required this.app,
  });

  final App app;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        if (app.canUpdate)
          PillWidget(
            text: WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    app.localApp.value!.installedVersion!,
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 11,
                    ),
                  ),
                ],
              ),
            ),
            size: 10,
            color: Colors.grey[800]!,
          ),
        if (app.canUpdate) Icon(Icons.arrow_right),
        if (app.latestMetadata != null)
          PillWidget(
            text: WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    app.latestMetadata!.version!,
                    style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 11,
                    ),
                  ),
                  Row(
                    children: [
                      Gap(5),
                      if (app.canUpdate) Icon(Icons.update_outlined, size: 15),
                      if (app.canInstall)
                        Icon(Icons.download_rounded, size: 15),
                      if (app.isUpdated) Icon(Icons.check, size: 15),
                    ],
                  ),
                ],
              ),
            ),
            size: 10,
            color: app.canUpdate
                ? kUpdateColor
                : (app.canInstall ? Colors.grey[800]! : kUpdateColor),
          ),
      ],
    );
  }
}
