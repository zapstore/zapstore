import 'package:flutter_data/flutter_data.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:ndk/domain_layer/usecases/nwc/consts/nwc_method.dart';
import 'package:ndk/ndk.dart';

const kNwcSecretKey = 'nwc_secret';

class NwcConnectionNotifier extends StateNotifier<AsyncValue<NwcConnection?>> {
  final _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );

  NwcConnectionNotifier() : super(AsyncData(null)) {
    // Attempt to load from local storage on initialization
    _storage
        .read(key: kNwcSecretKey)
        .then(connectWallet)
        .catchError((e, stack) {
      state = AsyncError(e, stack);
    });
  }

  Future<void> connectWallet([String? nwcSecret]) async {
    if (nwcSecret == null) {
      // We get here when storage has no secret, do nothing
      state = AsyncData(null);
      return;
    }

    state = AsyncValue.loading();

    // If secret is supplied and a connection is still active,
    // it means it is a new connection string so disconnect NWC
    if (state.value != null) {
      await ndkForNwc.nwc.disconnect(state.value!);
    }

    // Write secret to storage and connect
    await _storage.write(key: kNwcSecretKey, value: nwcSecret);

    final connection = await ndkForNwc.nwc.connect(
      nwcSecret,
      doGetInfoMethod: false,
      onError: (error) {
        state =
            AsyncValue.error(error ?? 'Could not connect', StackTrace.current);
      },
    );
    if (connection.permissions.contains(NwcMethod.PAY_INVOICE.name)) {
      state = AsyncValue.data(connection);
    } else {
      state = AsyncError('No permission to zap', StackTrace.current);
    }
  }

  Future<void> disconnectWallet() async {
    await _storage.delete(key: kNwcSecretKey);
    state = AsyncData(null);
  }
}

final nwcConnectionProvider =
    StateNotifierProvider<NwcConnectionNotifier, AsyncValue<NwcConnection?>>(
        (ref) => NwcConnectionNotifier());

final ndkForNwc = Ndk(
  NdkConfig(
      cache: MemCacheManager(),
      eventVerifier: Bip340EventVerifier(),
      bootstrapRelays: [],
      logLevel: LogLevels().info),
);
