import 'package:flutter/material.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class UsersRichText extends StatelessWidget {
  const UsersRichText({
    super.key,
    this.leadingText,
    this.trailingText,
    required this.users,
  });

  final String? leadingText;
  final String? trailingText;
  final List<User> users;

  @override
  Widget build(BuildContext context) {
    return RichText(
      text: TextSpan(
        children: [
          if (leadingText != null)
            TextSpan(text: leadingText!, style: TextStyle(fontSize: 15)),
          for (final user in users)
            TextSpan(
              children: [
                WidgetSpan(
                  alignment: PlaceholderAlignment.middle,
                  child: Wrap(
                    spacing: 1,
                    runSpacing: 10,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      RoundedImage(url: user.avatarUrl, size: 20),
                      Text(
                        ' ${user.nameOrNpub}',
                        style: TextStyle(
                            fontSize: 15, fontWeight: FontWeight.bold),
                      ),
                      Text(
                        users.indexOf(user) == users.length - 1
                            ? ''
                            : (users.indexOf(user) == users.length - 2
                                ? ' and '
                                : ', '),
                        style: TextStyle(fontSize: 15),
                      ),
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
