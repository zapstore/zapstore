import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class AuthorContainer extends StatelessWidget {
  final User user;
  final String text;
  final bool oneLine;
  final double size;

  const AuthorContainer({
    super.key,
    required this.user,
    this.text = 'Signed by',
    this.oneLine = true,
    this.size = 14,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(top: 4, bottom: 4),
      child: Row(
        children: [
          RoundedImage(url: user.avatarUrl, size: oneLine ? (size * 1.5) : 46),
          Gap(10),
          if (oneLine)
            Expanded(
              child: Text(
                '$text ${user.nameOrNpub}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: size),
              ),
            ),
          if (!oneLine)
            Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(text),
                Padding(
                  padding: const EdgeInsets.only(left: 1),
                  child: Text(
                    user.nameOrNpub,
                    style: TextStyle(fontWeight: FontWeight.bold),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            )
        ],
      ),
    );
  }
}
