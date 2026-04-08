import 'package:flutter/foundation.dart';

/// Converts a deep link URI into a router path string, or returns null if
/// the URI is not a recognized deep link.
///
/// Handles:
/// - `https://zapstore.dev/apps/{id}` and `.../stacks/{id}`
/// - `/apps/{id}` and `/stacks/{id}` (path-only, as seen by GoRouter's onException)
/// - `market://details?id=com.example.app`
/// - `market://search?q=search+query`
String? resolveDeepLinkPath(Uri uri) {
  // https://zapstore.dev/apps/<id>  OR  bare /apps/<id> from GoRouter
  // https://zapstore.dev/stacks/<id>  OR  bare /stacks/<id>
  if (uri.pathSegments.length == 2) {
    final section = uri.pathSegments[0];
    if (section == 'apps' || section == 'stacks') {
      final isFullUri = uri.scheme == 'https' && uri.host == 'zapstore.dev';
      final isBarePath = uri.host.isEmpty;
      if (isFullUri || isBarePath) {
        final id = uri.pathSegments[1];
        if (id.isNotEmpty) {
          final route = section == 'apps' ? 'app' : 'stack';
          return '/search/$route/$id';
        }
      }
    }
  }

  // market://details?id=com.example.app  (Google Play-style intents)
  if (uri.scheme == 'market') {
    if (uri.host == 'details' || uri.path == '/details') {
      final id = uri.queryParameters['id'];
      if (id != null && id.isNotEmpty) {
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
