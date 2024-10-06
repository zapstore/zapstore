import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:gap/gap.dart';
import 'package:intl/intl.dart';
import 'package:zapstore/models/release.dart';

class ReleaseCard extends StatelessWidget {
  ReleaseCard({
    super.key,
    required this.release,
  });

  final Release release;
  final formatter = DateFormat('dd MMM yyyy');

  @override
  Widget build(BuildContext context) {
    final metadata = release.app.value!.latestMetadata;
    return Card(
      margin: EdgeInsets.only(top: 8, bottom: 8),
      elevation: 0,
      child: Container(
        padding: EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: Colors.grey[900],
          borderRadius: BorderRadius.circular(10),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(release.version,
                style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold)),
            Gap(10),
            release.content.length < 3000
                ? MarkdownBody(data: release.content)
                : Container(
                    constraints: BoxConstraints(
                      maxHeight: 300,
                    ),
                    child: Markdown(data: release.content),
                  ),
            Gap(30),
            if (metadata != null)
              Padding(
                padding: const EdgeInsets.all(8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [Text('Version'), Text(metadata.version!)],
                ),
              ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Date'),
                  Text(formatter.format(release.createdAt!)),
                ],
              ),
            ),
            if (metadata != null)
              Padding(
                padding: const EdgeInsets.all(8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('Size'),
                    Text('${metadata.size! ~/ 1024 ~/ 1024} MB')
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}
