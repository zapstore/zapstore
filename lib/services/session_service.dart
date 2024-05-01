import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/models/user.dart';

final loggedInUser = StateProvider<User?>((ref) => null);
