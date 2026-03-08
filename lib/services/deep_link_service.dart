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
  /// The initial launch URI is not re-processed here because GoRouter's
  /// top-level redirect already maps it to the correct path before any
  /// screen renders. Only links received while the app is running are handled.
  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;

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
