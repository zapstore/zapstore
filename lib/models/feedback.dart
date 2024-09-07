import 'package:purplebase/purplebase.dart';

class AppFeedback extends BaseEvent {
  @override
  int get kind => 1011;

  AppFeedback({
    super.id,
    super.content,
  });
}
