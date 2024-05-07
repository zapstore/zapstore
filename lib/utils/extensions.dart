import 'package:flutter/material.dart';
import 'package:flutter_styled_toast/flutter_styled_toast.dart';

extension ContextX on BuildContext {
  ThemeData get theme => Theme.of(this);
  void showError(String message) {
    showToast(
      message,
      duration: Duration(seconds: 7),
      textStyle: TextStyle(fontWeight: FontWeight.bold, fontSize: 18),
      animDuration: Duration(milliseconds: 300),
      animation: StyledToastAnimation.fade,
      reverseAnimation: StyledToastAnimation.fade,
      backgroundColor: Color.fromARGB(230, 244, 67, 54),
      position: StyledToastPosition.center,
      context: this,
    );
  }
}
