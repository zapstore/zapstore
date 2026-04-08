import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/services/deep_link_resolver.dart';

final deepLinkServiceProvider = Provider<DeepLinkService>((ref) {
  return DeepLinkService(ref);
});

class DeepLinkService {
  final Ref _ref;
  final AppLinks _appLinks = AppLinks();
  StreamSubscription<Uri>? _subscription;
  bool _initialized = false;

  DeepLinkService(this._ref);

  GoRouter get _router => _ref.read(routerProvider);

  /// Initialize the service - call once after app is ready.
  ///
  /// Handles both the cold-launch URI (the link that opened the app) and
  /// links received while the app is already running.
  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;

    final initialUri = await _appLinks.getInitialLink();
    _handleUri(initialUri);

    _subscription = _appLinks.uriLinkStream.listen(_handleUri);
  }

  void _handleUri(Uri? uri) {
    if (uri == null) return;

    final path = resolveDeepLinkPath(uri);
    if (path != null) {
      _router.go(path);
    }
  }

  void dispose() {
    _subscription?.cancel();
  }
}
