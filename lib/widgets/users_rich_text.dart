import 'package:collection/collection.dart';
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
  final bool onlyUseCommaSeparator;

  const UsersRichText({
    super.key,
    this.leadingTextSpan,
    this.trailingText,
    required this.users,
    this.signedInUser,
    this.maxUsersToDisplay,
    this.fontSize = 15,
    this.onlyUseCommaSeparator = false,
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

    final spans = <InlineSpan>[];

    if (leadingTextSpan != null) {
      spans.add(leadingTextSpan!);
      spans.add(TextSpan(text: ' '));
    }

    usersToDisplay.forEachIndexed((i, user) {
      if (user == signedInUser) {
        spans.add(TextSpan(
            text: leadingTextSpan != null ? 'you' : 'You',
            style: TextStyle(fontWeight: FontWeight.bold)));
      } else {
        spans.add(
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            // Add a wrap in order to treat it as a non-breaking block
            child: Wrap(
              children: [
                Padding(
                  padding: const EdgeInsets.only(right: 4),
                  child: RoundedImage(url: user.avatarUrl, size: 20),
                ),
                Text(
                  user.nameOrNpub,
                  style: TextStyle(
                    fontSize: fontSize,
                    fontWeight: FontWeight.bold,
                    height: 1.25,
                  ),
                ),
              ],
            ),
          ),
        );
      }

      // Before the last span
      if (i < usersToDisplay.length - 2) {
        spans.add(TextSpan(text: ', '));
      }

      if (i == usersToDisplay.length - 2) {
        // If onlyUseCommaSeparator, or users length goes over max to display,
        // then add a comma because we'll later add "and others"
        if (onlyUseCommaSeparator || usersToDisplay.length < users.length) {
          spans.add(TextSpan(text: ', '));
        } else {
          spans.add(TextSpan(text: ' and '));
        }
      }

      // Last span
      if (i == usersToDisplay.length - 1) {
        if (usersToDisplay.length < users.length) {
          final remainingUsersLength = users.length - usersToDisplay.length;
          spans.add(TextSpan(text: ' and $remainingUsersLength others'));
        }
        if (trailingText != null) {
          spans.add(TextSpan(text: trailingText));
        }
      }
    });

    return Text.rich(
      TextSpan(
        style: TextStyle(fontSize: fontSize, height: 1.7),
        children: spans,
      ),
    );
  }
}
