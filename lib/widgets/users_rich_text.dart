import 'package:flutter/material.dart';
import 'package:zapstore/models/user.dart';
import 'package:zapstore/widgets/rounded_image.dart';

class UsersRichText extends StatelessWidget {
  final TextSpan? leadingTextSpan;
  final String? trailingText;
  final List<User> users;
  final User? signedInUser;
  final int? maxUsersToDisplay;
  final double fontSize;

  const UsersRichText({
    super.key,
    this.leadingTextSpan,
    this.trailingText,
    required this.users,
    this.signedInUser,
    this.maxUsersToDisplay,
    this.fontSize = 16,
  });

  @override
  Widget build(BuildContext context) {
    // Move signed in user to head of list
    if (users.remove(signedInUser)) {
      users.insert(0, signedInUser!);
    }
    final usersToDisplay = maxUsersToDisplay != null
        ? users.take(maxUsersToDisplay!).toList()
        : users;

    return RichText(
      text: TextSpan(
        children: [
          for (final user in usersToDisplay)
            WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: Wrap(
                spacing: 0,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  if (usersToDisplay.indexOf(user) == 0 &&
                      leadingTextSpan != null)
                    RichText(text: leadingTextSpan!),
                  if (user != signedInUser)
                    Padding(
                      padding: const EdgeInsets.only(left: 2, right: 2),
                      child: RoundedImage(url: user.avatarUrl, size: 20),
                    ),
                  Text(
                    user == signedInUser
                        ? (leadingTextSpan != null ? ' you' : 'You')
                        : ' ${user.nameOrNpub}',
                    style: TextStyle(
                        fontSize: fontSize, fontWeight: FontWeight.bold),
                  ),
                  Text(
                      usersToDisplay.indexOf(user) == usersToDisplay.length - 1
                          ? ''
                          : (usersToDisplay.indexOf(user) ==
                                  usersToDisplay.length - 2
                              ? usersToDisplay.length < users.length
                                  ? ', '
                                  : ' and '
                              : ', '),
                      style: TextStyle(fontSize: fontSize)),
                  if (usersToDisplay.indexOf(user) == usersToDisplay.length - 1)
                    Text(
                        '${usersToDisplay.length < users.length ? ' and ${users.length - usersToDisplay.length} others' : ''}${trailingText ?? ''}',
                        style: TextStyle(fontSize: fontSize))
                ],
              ),
            ),
        ],
      ),
    );
  }
}
