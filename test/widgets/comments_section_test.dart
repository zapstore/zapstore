import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/widgets/comments_section.dart';

void main() {
  setUpAll(() {
    Model.register(
      kind: 1111,
      constructor: Comment.fromMap,
      partialConstructor: PartialComment.fromMap,
    );
  });

  test('tagAppComment adds the app id as i', () {
    final comment = PartialComment(content: 'Works well');

    tagAppComment(comment, 'com.example.app');

    expect(comment.event.getFirstTagValue('i'), 'com.example.app');
  });
}
