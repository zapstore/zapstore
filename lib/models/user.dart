import 'package:equatable/equatable.dart';
import 'package:flutter_data/flutter_data.dart';
import 'package:json_annotation/json_annotation.dart';

part 'user.g.dart';

@JsonSerializable()
@DataRepository([UserAdapter])
class User extends DataModel<User> with EquatableMixin {
  final String id;
  String? name;
  String? pictureUrl;
  String? nip05;
  @DataRelationship(inverse: 'followers')
  final HasMany<User> following;
  @DataRelationship(inverse: 'following')
  final HasMany<User> followers;

  User({required this.id, HasMany<User>? following, HasMany<User>? followers})
      : this.following = following ?? HasMany(),
        this.followers = followers ?? HasMany();

  @override
  List<Object?> get props => [id];
}

mixin UserAdapter on RemoteAdapter<User> {
  @override
  DataStateNotifier<List<User>> watchAllNotifier(
      {bool? remote,
      Map<String, dynamic>? params,
      Map<String, String>? headers,
      bool? syncLocal,
      String? finder,
      DataRequestLabel? label}) {
    return super.watchAllNotifier(
        remote: remote,
        params: params,
        headers: headers,
        syncLocal: syncLocal,
        finder: finder,
        label: label);
  }
}
