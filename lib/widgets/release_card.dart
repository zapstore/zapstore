import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:gap/gap.dart';
import 'package:intl/intl.dart';
import 'package:url_launcher/url_launcher.dart';
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
            if (DateTime.now().difference(release.event.createdAt) >
                Duration(days: 365))
              Padding(
                padding: const EdgeInsets.all(8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.start,
                  children: [
                    Icon(Icons.warning_rounded, size: 18),
                    Gap(10),
                    Text('This release is over a year old',
                        style: TextStyle(
                          color: Colors.red[100],
                          fontWeight: FontWeight.bold,
                        )),
                  ],
                ),
              ),
            Gap(10),
            release.releaseNotes.length < 3000
                ? MarkdownBody(
                    data: release.releaseNotes,
                    onTapLink: (text, url, title) {
                      if (url != null) {
                        launchUrl(Uri.parse(url));
                      }
                    },
                  )
                : Container(
                    constraints: BoxConstraints(
                      maxHeight: 300,
                    ),
                    child: Markdown(data: release.releaseNotes),
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
            Padding(
              padding: const EdgeInsets.all(8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('Date'),
                  Text(formatter.format(release.event.createdAt)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
