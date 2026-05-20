/// Internal: parses Namecoin / `.bit` identifiers into the
/// `(namecoinName, localPart, isDomain)` triple used by the resolver.
///
/// Accepted shapes (case-insensitive, optionally prefixed with
/// `nostr:`):
///
///   * `<anything>.bit`
///   * `alice@<anything>.bit`
///   * `d/<name>`
///   * `id/<name>`
class ParsedIdentifier {
  /// The Namecoin name to query, e.g. `d/example` or `id/alice`.
  final String namecoinName;

  /// The local-part within the name's value. `_` means "the root" /
  /// "the name itself" per the NIP-05 / `.bit` convention.
  final String localPart;

  /// `true` for `d/` names (domain namespace, expecting a `names`
  /// map); `false` for `id/` names (identity namespace).
  final bool isDomain;

  /// Creates a [ParsedIdentifier] with the given fields.
  const ParsedIdentifier({
    required this.namecoinName,
    required this.localPart,
    required this.isDomain,
  });
}

/// Returns `true` when [identifier] should be routed to Namecoin
/// resolution instead of DNS-based NIP-05.
///
/// The check is intentionally cheap so callers can use it as a
/// front-door check in hot paths.
bool isBitIdentifier(String? identifier) {
  if (identifier == null || identifier.isEmpty) return false;
  var s = identifier.trim().toLowerCase();
  if (s.startsWith('nostr:')) s = s.substring(6);
  if (s.startsWith('d/') || s.startsWith('id/')) return true;
  return s.endsWith('.bit');
}

/// Parses [raw] into a [ParsedIdentifier], or returns `null` if the
/// shape is not a Namecoin identifier.
ParsedIdentifier? parseIdentifier(String raw) {
  var input = raw.trim();
  if (input.length >= 6 && input.substring(0, 6).toLowerCase() == 'nostr:') {
    input = input.substring(6);
  }
  final lower = input.toLowerCase();

  if (lower.startsWith('d/')) {
    return ParsedIdentifier(
      namecoinName: lower,
      localPart: '_',
      isDomain: true,
    );
  }
  if (lower.startsWith('id/')) {
    return ParsedIdentifier(
      namecoinName: lower,
      localPart: '_',
      isDomain: false,
    );
  }

  // user@domain.bit
  if (input.contains('@') && lower.endsWith('.bit')) {
    final atIdx = input.indexOf('@');
    final localRaw = input.substring(0, atIdx);
    final local = localRaw.isEmpty ? '_' : localRaw.toLowerCase();
    final domain = input
        .substring(atIdx + 1)
        .toLowerCase()
        .replaceFirst(RegExp(r'\.bit$'), '');
    if (domain.isEmpty) return null;
    return ParsedIdentifier(
      namecoinName: 'd/$domain',
      localPart: local,
      isDomain: true,
    );
  }

  // bare.bit
  if (lower.endsWith('.bit')) {
    final domain = lower.replaceFirst(RegExp(r'\.bit$'), '');
    if (domain.isEmpty) return null;
    return ParsedIdentifier(
      namecoinName: 'd/$domain',
      localPart: '_',
      isDomain: true,
    );
  }

  return null;
}
