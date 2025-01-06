import 'package:purplebase/purplebase.dart';

class AppFeedback extends RegularEvent<AppFeedback> {
  AppFeedback.fromJson(super.map) : super.fromJson();
}

class PartialAppFeedback extends RegularPartialEvent<AppFeedback> {
  PartialAppFeedback({
    required String content,
  }) {
    event.content = content;
  }
}
