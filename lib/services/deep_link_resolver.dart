import 'package:models/models.dart';
import 'package:zapstore/services/log_service.dart';

/// 64-character hex pubkey (case-insensitive). Normalised to lowercase by
/// the resolver since downstream Nostr filters require it.
final _hexPubkey64 = RegExp(r'^[0-9a-fA-F]{64}$');

/// Converts a deep link URI into a router path string, or returns null if
/// the URI is not a recognized deep link.
///
/// Handles:
/// - `https://zapstore.dev/apps/{id}` and `.../stacks/{id}`
/// - `https://zapstore.dev/apps?q=foo` (search via query parameter)
/// - `https://zapstore.dev/stacks` (browse all stacks)
/// - `https://zapstore.dev/profile/{npub}` (user profile)
/// - Equivalent bare paths (as seen by GoRouter's `onException`)
/// - `market://details?id=com.example.app`
/// - `market://search?q=search+query`
String? resolveDeepLinkPath(Uri uri) {
  final isZapstoreHttps =
      uri.scheme == 'https' && uri.host == 'zapstore.dev';
  final isBarePath = uri.scheme.isEmpty && uri.host.isEmpty;
  final isZapstoreUri = isZapstoreHttps || isBarePath;

  // /apps  or  /apps?q=<query>  — search entry point
  if (isZapstoreUri &&
      uri.pathSegments.length == 1 &&
      uri.pathSegments[0] == 'apps') {
    return _searchPath(uri.queryParameters['q']);
  }

  // /stacks  — browse all stacks
  if (isZapstoreUri &&
      uri.pathSegments.length == 1 &&
      uri.pathSegments[0] == 'stacks') {
    return '/search/stacks';
  }

  // /apps/<id>  or  /stacks/<id>
  if (isZapstoreUri && uri.pathSegments.length == 2) {
    final section = uri.pathSegments[0];
    if (section == 'apps' || section == 'stacks') {
      final id = uri.pathSegments[1];
      if (id.isNotEmpty) {
        final route = section == 'apps' ? 'app' : 'stack';
        return '/search/$route/$id';
      }
    }

    // /profile/<npub-or-hex-pubkey> — validated and normalised to hex.
    if (section == 'profile') {
      final hex = _tryParsePubkey(uri.pathSegments[1]);
      if (hex != null) return '/search/user/$hex';
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
      if (query != null && query.trim().isNotEmpty) {
        LogService.I.debug(
          'market intent: search query',
          tag: 'deep_link',
          fields: {'query': query},
        );
        return _searchPath(query);
      }
    }

    LogService.I.debug(
      'market intent: unhandled URI',
      tag: 'deep_link',
      fields: {'uri': uri.toString()},
    );
  }

  return null;
}

/// Returns the lowercase hex pubkey for [id] if it is either a valid
/// `npub1…` or a 64-character hex pubkey. Returns `null` for any other
/// shape — including `nprofile`, `nevent`, `naddr`, malformed bech32,
/// and arbitrary strings — so `UserScreen` is never instantiated with
/// a non-pubkey value.
String? _tryParsePubkey(String id) {
  if (id.isEmpty) return null;
  if (_hexPubkey64.hasMatch(id)) return id.toLowerCase();
  if (id.startsWith('npub1')) {
    try {
      final decoded = Utils.decodeShareableIdentifier(id);
      if (decoded is ProfileData) return decoded.pubkey;
    } catch (_) {
      // Fall through to null.
    }
  }
  return null;
}

/// Build a `/search` path, optionally seeded with a `?q=` query parameter.
String _searchPath(String? query) {
  final trimmed = query?.trim() ?? '';
  if (trimmed.isEmpty) return '/search';
  return Uri(path: '/search', queryParameters: {'q': trimmed}).toString();
}
