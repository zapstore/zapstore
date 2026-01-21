/// Utility functions for WebSocket relay URL handling.

/// Validates and normalizes a relay URL: checks format, removes trailing
/// slashes, lowercases the host. Returns null if invalid.
///
/// Examples:
/// - `  WSS://Relay.Example.Com/  ` → `wss://relay.example.com`
/// - `wss://relay.example.com/` → `wss://relay.example.com`
/// - `wss://relay.example.com:443/path/` → `wss://relay.example.com:443/path`
/// - `invalid-url` → `null`
String? validateAndNormalizeRelayUrl(String input) {
  final trimmed = input.trim();
  if (trimmed.isEmpty) return null;

  final uri = Uri.tryParse(trimmed);
  if (uri == null) return null;

  // Must have ws or wss scheme
  if (uri.scheme != 'ws' && uri.scheme != 'wss') return null;

  // Must have a host
  if (uri.host.isEmpty) return null;
  if (!_isValidRelayHost(uri.host)) return null;

  // Rebuild normalized URL: scheme://host[:port][/path]
  final buffer = StringBuffer()
    ..write(uri.scheme)
    ..write('://')
    ..write(uri.host.toLowerCase());

  if (uri.hasPort && uri.port != 0) {
    buffer
      ..write(':')
      ..write(uri.port);
  }

  // Add path if present, but remove trailing slashes
  var path = uri.path;
  while (path.endsWith('/')) {
    path = path.substring(0, path.length - 1);
  }
  if (path.isNotEmpty) {
    buffer.write(path);
  }

  return buffer.toString();
}

bool _isValidRelayHost(String host) {
  if (host == 'localhost') return true;

  // IPv4 validation (e.g., 192.168.0.1)
  final ipv4Match = RegExp(r'^\d{1,3}(\.\d{1,3}){3}$').hasMatch(host);
  if (ipv4Match) {
    final parts = host.split('.');
    return parts.every((p) {
      final value = int.tryParse(p);
      return value != null && value >= 0 && value <= 255;
    });
  }

  // Domain validation
  if (host.length > 253) return false;
  final labels = host.split('.');
  if (labels.length < 2) return false;
  for (final label in labels) {
    if (label.isEmpty || label.length > 63) return false;
    if (!RegExp(r'^[a-zA-Z0-9-]+$').hasMatch(label)) return false;
    if (label.startsWith('-') || label.endsWith('-')) return false;
  }
  return true;
}

/// Normalizes a relay URL for comparison, including default ports.
/// Returns a canonical form: scheme://host:port/path
/// where port is always explicit (defaults: 443 for wss, 80 for ws).
String? _normalizeForComparison(String url) {
  final normalized = validateAndNormalizeRelayUrl(url);
  if (normalized == null) return null;

  final uri = Uri.parse(normalized);
  final defaultPort = uri.scheme == 'wss' ? 443 : 80;
  final port = uri.hasPort && uri.port != 0 ? uri.port : defaultPort;

  final buffer = StringBuffer()
    ..write(uri.scheme)
    ..write('://')
    ..write(uri.host.toLowerCase())
    ..write(':')
    ..write(port);

  var path = uri.path;
  while (path.endsWith('/')) {
    path = path.substring(0, path.length - 1);
  }
  if (path.isNotEmpty) {
    buffer.write(path);
  }

  return buffer.toString();
}

/// Checks if a relay URL already exists in the set.
/// Normalizes both URLs before comparison, considering default ports.
/// Treats wss://relay.com and wss://relay.com:443 as duplicates.
bool isDuplicateRelay(String normalizedUrl, Set<String> existingRelays) {
  final newUrlCanonical = _normalizeForComparison(normalizedUrl);
  if (newUrlCanonical == null) return false;

  for (final existing in existingRelays) {
    final existingCanonical = _normalizeForComparison(existing);
    if (existingCanonical == null) continue;

    if (existingCanonical == newUrlCanonical) {
      return true;
    }
  }
  return false;
}

/// Formats a relay URL for display by removing the scheme and trailing slash.
///
/// Example: `wss://relay.example.com/` → `relay.example.com`
String formatRelayUrlShort(String relayUrl) {
  return relayUrl
      .replaceAll('wss://', '')
      .replaceAll('ws://', '')
      .replaceAll(RegExp(r'/$'), '');
}
