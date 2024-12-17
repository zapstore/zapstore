import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class NwcSecretNotifier extends StateNotifier<String?> {
  final _storage = const FlutterSecureStorage();

  NwcSecretNotifier() : super(null) {
    _loadNwcSecret();
  }

  Future<void> _loadNwcSecret() async {
    final secret = await _storage.read(key: 'nwc_secret');
    state = secret;
  }

  Future<void> updateNwcSecret(String? newSecret) async {
    if (newSecret != null) {
      await _storage.write(key: 'nwc_secret', value: newSecret);
    } else {
      await _storage.delete(key: 'nwc_secret');
    }
    await _loadNwcSecret();
  }
}