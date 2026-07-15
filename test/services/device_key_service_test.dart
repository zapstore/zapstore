import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/device_key_service.dart';

void main() {
  final service = DeviceKeyService();

  test('parses a copied nsec into lower-case hex', () {
    const hex =
        '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';
    expect(service.parsePrivateKey(bech32Encode('nsec', hex)), hex);
  });

  test('rejects malformed nsec checksums', () {
    expect(service.parsePrivateKey('nsec1invalid'), isNull);
  });
}
