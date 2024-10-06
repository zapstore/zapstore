import 'package:flutter/material.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:toastification/toastification.dart';

extension ContextX on BuildContext {
  ThemeData get theme => Theme.of(this);
  void showInfo(String message, {Icon? icon}) {
    toastification.show(
      context: this,
      type: ToastificationType.info,
      icon: icon ?? Icon(Icons.info),
      style: ToastificationStyle.fillColored,
      title: Text(
        message,
        style: TextStyle(fontSize: 16),
      ),
      autoCloseDuration: const Duration(seconds: 3),
      showProgressBar: false,
      closeOnClick: true,
      alignment: Alignment.bottomCenter,
    );
  }

  void showError(String message, {Icon? icon}) {
    toastification.show(
      context: this,
      type: ToastificationType.error,
      style: ToastificationStyle.fillColored,
      icon: icon ?? Icon(Icons.error),
      title: Text(
        message,
        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
      ),
      showProgressBar: false,
      closeOnClick: true,
      alignment: Alignment.lerp(Alignment.bottomCenter, Alignment.center, 0.25),
    );
  }
}

BelongsTo<T> belongsTo<T extends DataModelMixin<T>>(Map<String, dynamic> map) {
  return BelongsTo<T>.fromJson(map);
}

HasMany<T> hasMany<T extends DataModelMixin<T>>(Map<String, dynamic> map) {
  return HasMany<T>.fromJson(map);
}

const kI = "e593c54f840b32054dcad0fac15d57e4ac6523e31fe26b3087de6b07a2e9af58";
