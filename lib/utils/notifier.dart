import 'package:flutter_data/flutter_data.dart';

abstract class PreloadingStateNotifier<T> extends StateNotifier<AsyncValue<T>> {
  final Ref ref;
  PreloadingStateNotifier(this.ref) : super(AsyncLoading()) {
    state = fetchLocal();
    fetchRemote();
  }

  AsyncValue<T> fetchLocal();
  Future<void> fetchRemote();
}
