import 'package:purplebase/purplebase.dart';

class AppFeedback extends BaseEvent<AppFeedback> {
  @override
  int get kind => 1011;

  AppFeedback({
    required String content,
  }) : super(content: content);
}
