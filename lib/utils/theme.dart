import 'package:flutter/material.dart';

const kBackgroundColor = Color.fromARGB(255, 6, 6, 6);

final theme = ThemeData(
  primarySwatch: Colors.lightBlue,
  brightness: Brightness.dark,
  fontFamily: 'Inter',
  useMaterial3: true,
  scaffoldBackgroundColor: kBackgroundColor,
  visualDensity: VisualDensity.adaptivePlatformDensity,
);
