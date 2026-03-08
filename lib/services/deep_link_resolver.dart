import 'package:flutter/foundation.dart';

/// Converts a deep link URI into a router path string, or returns null if
/// the URI is not a recognized deep link.
///
/// Handles:
/// - `https://zapstore.dev/apps/{id}`
/// - `market://details?id=com.example.app`
/// - `market://search?q=search+query`
String? resolveDeepLinkPath(Uri uri) {
  // https://zapstore.dev/apps/<id>
  if (uri.scheme == 'https' &&
      uri.host == 'zapstore.dev' &&
      uri.pathSegments.length == 2 &&
      uri.pathSegments[0] == 'apps') {
    final id = uri.pathSegments[1];
    if (id.isNotEmpty) {
      debugPrint('App link: app ID = $id');
      return '/search/app/$id';
    }
  }

  // market://details?id=com.example.app  (Google Play-style intents)
  if (uri.scheme == 'market') {
    if (uri.host == 'details' || uri.path == '/details') {
      final id = uri.queryParameters['id'];
      if (id != null && id.isNotEmpty) {
        debugPrint('Market intent: app ID = $id');
        return '/search/app/$id';
      }
    }

    if (uri.host == 'search' || uri.path == '/search') {
      final query = uri.queryParameters['q'];
      if (query != null && query.isNotEmpty) {
        debugPrint('Market intent: search query = $query');
        return '/search/app/$query';
      }
    }

    debugPrint('Market intent: unhandled URI = $uri');
  }

  return null;
}
