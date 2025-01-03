import 'package:flutter/material.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class UsersRichText extends StatelessWidget {
  const UsersRichText({
    super.key,
    this.preSpan,
    this.trailingText,
    required this.users,
  });

  final TextSpan? preSpan;
  final String? trailingText;
  final List<User> users;

  @override
  Widget build(BuildContext context) {
    return RichText(
      text: TextSpan(
        children: [
          if (preSpan != null) preSpan!,
          for (final user in users)
            TextSpan(
              style: TextStyle(height: 1.6),
              children: [
                WidgetSpan(
                  alignment: PlaceholderAlignment.middle,
                  child: Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      RoundedImage(url: user.avatarUrl, size: 20),
                      Text(
                        ' ${user.nameOrNpub}',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      Text(users.indexOf(user) == users.length - 1
                          ? ''
                          : (users.indexOf(user) == users.length - 2
                              ? ' and '
                              : ', ')),
                    ],
                  ),
                ),
                if (users.indexOf(user) == users.length - 1)
                  TextSpan(text: trailingText, style: TextStyle(fontSize: 15))
              ],
            ),
        ],
      ),
    );
  }
}
