import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:ndk/domain_layer/usecases/nwc/consts/nwc_method.dart';
import 'package:ndk/ndk.dart';

AndroidOptions _getAndroidOptions() => const AndroidOptions(
  encryptedSharedPreferences: true,
);

class NwcSecretNotifier extends StateNotifier<String?> {
  final _storage = FlutterSecureStorage(aOptions: _getAndroidOptions());

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

class NwcConnectionNotifier extends StateNotifier<AsyncValue<NwcConnection>?> {
  Ref<AsyncValue<NwcConnection>?> ref;

  NwcConnectionNotifier(this.ref) : super(null) {
    ensureConnected(ref.watch(nwcSecretProvider));
  }

  Future<void> disconnect() async {
    state = null;
  }

  Future<void> ensureConnected(String? nwcSecret) async {
    if (nwcSecret != null && nwcSecret.isNotEmpty && state == null) {
      state = AsyncValue.loading();
      NwcConnection connection = await ndkForNwc.nwc
          .connect(nwcSecret, doGetInfoMethod: false, onError: (error) {
        state =
            AsyncValue.error(error ?? "couldn't connect", StackTrace.current);
      });
      if (connection.permissions.contains(NwcMethod.PAY_INVOICE.name)) {
        state = AsyncValue.data(connection);
      }
    } else {
      state = null;
    }
  }
}

final nwcSecretProvider =
    StateNotifierProvider<NwcSecretNotifier, String?>((ref) {
  return NwcSecretNotifier();
});

final nwcConnectionProvider =
    StateNotifierProvider<NwcConnectionNotifier, AsyncValue<NwcConnection>?>(
        (ref) {
  return NwcConnectionNotifier(ref);
});

final ndkForNwc = Ndk(
  NdkConfig(
      cache: MemCacheManager(),
      eventVerifier: Bip340EventVerifier(),
      bootstrapRelays: [],
      logLevel: LogLevels().info),
);
