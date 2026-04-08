import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:models/models.dart';
import 'package:url_launcher/url_launcher.dart';

import 'package:zapstore/constants/app_constants.dart';

/// Regex for zapstore.dev app/stack deep links.
final _zapstoreUrlPattern = RegExp(
  r'https?://zapstore\.dev/(apps|stacks)/(.+)',
  caseSensitive: false,
);

/// Resolves the current navigation branch from the GoRouter state so that
/// pushed routes stay within the active tab (search / updates / profile).
String _currentBranch(BuildContext context) {
  final segments = GoRouterState.of(context).uri.pathSegments;
  return segments.isNotEmpty ? segments.first : 'search';
}

/// Push to a user profile screen within the current branch.
void pushUser(BuildContext context, String pubkey) {
  context.push('/${_currentBranch(context)}/user/$pubkey');
}

/// Push to an app detail screen within the current branch.
///
/// When [author] is provided an naddr is built so the detail screen can
/// uniquely identify the app (identifier + author).
void pushApp(BuildContext context, String identifier, {String? author, int kind = 32267}) {
  final id = author != null
      ? Utils.encodeShareableIdentifier(
          AddressInput(
            identifier: identifier,
            author: author,
            kind: kind,
            relays: const [kDefaultRelay],
          ),
        )
      : identifier;
  context.push('/${_currentBranch(context)}/app/$id');
}

/// Push to a stack detail screen within the current branch.
void pushStack(BuildContext context, String identifier, {String? author, int kind = 30267}) {
  final id = author != null
      ? Utils.encodeShareableIdentifier(
          AddressInput(
            identifier: identifier,
            author: author,
            kind: kind,
            relays: const [],
          ),
        )
      : identifier;
  context.push('/${_currentBranch(context)}/stack/$id');
}

/// Push to the all-stacks screen within the current branch.
void pushStacks(BuildContext context) {
  context.push('/${_currentBranch(context)}/stacks');
}

/// Attempt to navigate in-app for a URL or Nostr identifier.
///
/// Handles:
/// - `https://zapstore.dev/apps/<id>` and `.../stacks/<id>`
/// - `nostr:naddr1...`, `nostr:npub1...`, bare NIP-19 tokens
///
/// Returns `true` if navigation was handled in-app, `false` otherwise.
/// When [fallbackLaunch] is true (the default), unrecognised URLs are opened
/// in an external browser.
bool navigateToContent(
  BuildContext context,
  String input, {
  bool fallbackLaunch = true,
}) {
  final cleaned = input.trim();

  // 1. zapstore.dev deep links
  final zapstoreMatch = _zapstoreUrlPattern.firstMatch(cleaned);
  if (zapstoreMatch != null) {
    final type = zapstoreMatch.group(1)!; // "apps" or "stacks"
    final id = zapstoreMatch.group(2)!;
    if (type == 'apps') {
      _navigateNip19OrIdentifier(context, id, fallbackKind: 'app');
    } else {
      _navigateNip19OrIdentifier(context, id, fallbackKind: 'stack');
    }
    return true;
  }

  // 2. nostr: prefix or bare NIP-19 token
  final nip19 = cleaned.replaceFirst('nostr:', '');
  if (_tryNavigateNip19(context, nip19)) return true;

  // 3. Not a recognised in-app link
  if (fallbackLaunch) {
    _launchExternal(cleaned);
  }
  return false;
}

/// Try to decode a NIP-19 token and navigate. Returns true on success.
bool _tryNavigateNip19(BuildContext context, String token) {
  try {
    final decoded = Utils.decodeShareableIdentifier(token);
    switch (decoded) {
      case AddressData(:final kind):
        if (kind == 30267) {
          pushStack(context, token);
        } else {
          pushApp(context, token);
        }
        return true;
      case ProfileData(:final pubkey):
        pushUser(context, pubkey);
        return true;
      case EventData():
        return false;
    }
  } catch (_) {}
  return false;
}

/// The id might be an naddr or a plain identifier (e.g. `com.example.app`).
void _navigateNip19OrIdentifier(BuildContext context, String id, {required String fallbackKind}) {
  if (_tryNavigateNip19(context, id)) return;
  final branch = _currentBranch(context);
  context.push('/$branch/$fallbackKind/$id');
}

void _launchExternal(String url) async {
  try {
    var cleanUrl = url;
    if (!cleanUrl.startsWith('http://') && !cleanUrl.startsWith('https://')) {
      cleanUrl = 'https://$cleanUrl';
    }
    final uri = Uri.parse(cleanUrl);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } else {
      try {
        await launchUrl(uri, mode: LaunchMode.platformDefault);
      } catch (_) {}
    }
  } catch (_) {}
}
