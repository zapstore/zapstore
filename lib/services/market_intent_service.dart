import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:flutter/foundation.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/router.dart';

/// Provider that initializes market:// intent handling
/// 
/// Listens for incoming market:// URIs (e.g., market://details?id=com.example.app)
/// and navigates to the app detail screen.
final marketIntentServiceProvider = Provider<MarketIntentService>((ref) {
  return MarketIntentService(ref);
});

class MarketIntentService {
  final Ref _ref;
  final AppLinks _appLinks = AppLinks();
  StreamSubscription<Uri>? _subscription;
  bool _initialized = false;

  MarketIntentService(this._ref);

  GoRouter get _router => _ref.read(routerProvider);

  /// Initialize the service - call once after app is ready
  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;

    // Handle initial link (app launched via intent)
    final initialUri = await _appLinks.getInitialLink();
    _handleUri(initialUri);

    // Handle links while app is running
    _subscription = _appLinks.uriLinkStream.listen(_handleUri);
  }

  void _handleUri(Uri? uri) {
    final packageId = _extractPackageId(uri);
    if (packageId != null) {
      _router.go('/search/app/$packageId');
    }
  }

  void dispose() {
    _subscription?.cancel();
  }
}

/// Extract package ID from a URI
/// 
/// Handles:
/// - market://details?id=com.example.app
/// - market://search?q=search+query
String? _extractPackageId(Uri? uri) {
  if (uri == null) return null;
  
  if (uri.scheme != 'market') return null;
  
  // market://details?id=com.example.app
  if (uri.host == 'details' || uri.path == '/details') {
    final id = uri.queryParameters['id'];
    if (id != null && id.isNotEmpty) {
      debugPrint('Market intent: app ID = $id');
      return id;
    }
  }
  
  // market://search?q=query
  if (uri.host == 'search' || uri.path == '/search') {
    final query = uri.queryParameters['q'];
    if (query != null && query.isNotEmpty) {
      debugPrint('Market intent: search query = $query');
      return query;
    }
  }
  
  debugPrint('Market intent: unhandled URI = $uri');
  return null;
}

