import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';

part 'user.g.dart';

@JsonSerializable()
@DataRepository([])
class User extends DataModel<User> {
  final String id;
  final String name;

  User({required this.id, required this.name});
}
