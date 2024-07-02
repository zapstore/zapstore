import 'dart:async';

const _debounceTime = 1200;
Timer? timer;
final acc = [];

void debounce<T>(T input, Future Function(List<T>) cb) {
  if (timer?.isActive ?? false) {
    timer!.cancel();
  }
  acc.add(input);
  timer = Timer(Duration(milliseconds: _debounceTime), () async {
    await cb(acc.cast<T>());
    acc.clear();
  });
}
