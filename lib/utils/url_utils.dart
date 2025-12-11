/// Lightweight URL validation helpers used across widgets to avoid invalid
/// image fetches (e.g. data: URIs or missing hosts).
String? sanitizeHttpUrl(String? url) {
  if (url == null) return null;
  final trimmed = url.trim();
  if (trimmed.isEmpty) return null;

  final uri = Uri.tryParse(trimmed);
  if (uri == null) return null;

  final scheme = uri.scheme.toLowerCase();
  final hasAllowedScheme = scheme == 'http' || scheme == 'https';
  if (!hasAllowedScheme || uri.host.isEmpty) return null;

  return trimmed;
}

/// Returns only valid HTTP(S) URLs from the provided list, preserving order.
List<String> filterValidHttpUrls(Iterable<String> urls) {
  final result = <String>[];
  for (final candidate in urls) {
    final sanitized = sanitizeHttpUrl(candidate);
    if (sanitized != null) {
      result.add(sanitized);
    }
  }
  return result;
}

/// Returns the first valid HTTP(S) URL from the provided list, or null.
String? firstValidHttpUrl(Iterable<String> urls) {
  for (final candidate in urls) {
    final sanitized = sanitizeHttpUrl(candidate);
    if (sanitized != null) return sanitized;
  }
  return null;
}

