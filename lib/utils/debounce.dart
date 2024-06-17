import 'dart:async';

const _debounceTime = 1200;
Timer? timer;
final acc = [];

void debounce<T>(T input, Function(List<T>) cb) {
  if (timer?.isActive ?? false) {
    timer!.cancel();
  }
  acc.add(input);
  timer = Timer(Duration(milliseconds: _debounceTime), () {
    cb(acc.cast<T>());
    acc.clear();
  });
}
