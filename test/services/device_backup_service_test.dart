import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/device_backup_service.dart';

void main() {
  test('backup exceptions retain a user-safe message', () {
    const exception = DeviceBackupException('Amber backup was unavailable.');
    expect(exception.toString(), 'Amber backup was unavailable.');
  });
}
