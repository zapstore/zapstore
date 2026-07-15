import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:models/models.dart';

const _storage = FlutterSecureStorage(
  aOptions: AndroidOptions(encryptedSharedPreferences: true),
  iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
);

const _kDeviceKey = 'device_key';

/// Manages the device private key. The nsec is intentionally isolated from
/// portable settings and temporary local state.
class DeviceKeyService {
  /// Load existing device key or generate a new one. Returns hex private key.
  Future<String> getOrCreatePrivateKey() async {
    final existing = await _storage.read(key: _kDeviceKey);
    if (existing != null && existing.isNotEmpty) return existing;

    final privateKeyHex = Utils.generateRandomHex64();
    await _storage.write(key: _kDeviceKey, value: privateKeyHex);
    return privateKeyHex;
  }

  /// Returns the bech32-encoded private key for display/copy.
  Future<String> getNsec() async {
    final hex = await getOrCreatePrivateKey();
    return bech32Encode('nsec', hex);
  }

  /// Replace the current device key (used during restore from backup).
  Future<void> replacePrivateKey(String privateKeyHex) async {
    await _storage.write(key: _kDeviceKey, value: privateKeyHex);
  }

  /// Parses either the internal 64-character hex form or a copied `nsec`.
  String? parsePrivateKey(String value) {
    final input = value.trim().replaceFirst(
      RegExp(r'^nostr:', caseSensitive: false),
      '',
    );
    if (RegExp(r'^[0-9a-fA-F]{64}$').hasMatch(input)) {
      return input.toLowerCase();
    }
    if (!input.toLowerCase().startsWith('nsec1')) return null;
    final values = <int>[];
    const alphabet = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
    for (final char in input.substring(5).toLowerCase().codeUnits) {
      final value = alphabet.indexOf(String.fromCharCode(char));
      if (value < 0) return null;
      values.add(value);
    }
    if (values.length < 6) return null;
    if (!_hasValidBech32Checksum('nsec', values)) return null;
    final payload = values.sublist(0, values.length - 6);
    final bytes = _convertBits(payload, from: 5, to: 8);
    if (bytes == null || bytes.length != 32) return null;
    return bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();
  }

  static List<int>? _convertBits(
    List<int> values, {
    required int from,
    required int to,
  }) {
    var accumulator = 0;
    var bits = 0;
    final output = <int>[];
    final maxValue = (1 << to) - 1;
    for (final value in values) {
      if (value < 0 || value >> from != 0) return null;
      accumulator = (accumulator << from) | value;
      bits += from;
      while (bits >= to) {
        bits -= to;
        output.add((accumulator >> bits) & maxValue);
      }
    }
    if (bits >= from || ((accumulator << (to - bits)) & maxValue) != 0) {
      return null;
    }
    return output;
  }

  static bool _hasValidBech32Checksum(String hrp, List<int> values) {
    const generators = [
      0x3b6a57b2,
      0x26508e6d,
      0x1ea119fa,
      0x3d4233dd,
      0x2a1462b3,
    ];
    var checksum = 1;
    final expandedHrp = <int>[
      ...hrp.codeUnits.map((codeUnit) => codeUnit >> 5),
      0,
      ...hrp.codeUnits.map((codeUnit) => codeUnit & 31),
    ];
    for (final value in [...expandedHrp, ...values]) {
      final top = checksum >> 25;
      checksum = (checksum & 0x1ffffff) << 5 ^ value;
      for (var i = 0; i < generators.length; i++) {
        if ((top >> i) & 1 == 1) checksum ^= generators[i];
      }
    }
    return checksum == 1;
  }
}

final deviceKeyServiceProvider = Provider<DeviceKeyService>(
  (ref) => DeviceKeyService(),
);

/// The device pubkey (hex). Available after storageReadyProvider resolves.
final devicePubkeyProvider = StateProvider<String?>((_) => null);
