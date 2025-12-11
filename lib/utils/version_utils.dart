// Version comparison

// SPDX-License-Identifier: MIT
/// Version comparison helper.
///
/// canUpgrade(installed, current) → true  if `current` is newer than `installed`.
///
/// The rules implemented are a pragmatic superset of Semantic Versioning:
///
/// • Optional `v`/`V` prefix is ignored (e.g. `v1.2.3`).
/// • The core version is an arbitrary number of dot-separated numeric parts,
///   compared *numerically*, not lexicographically (1.2.10 > 1.2.3).
/// • A version containing a pre-release label (`-alpha`, `-beta.1`, `-rc`,
///   etc.) is considered *older* than the same version without one
///   (1.0.0 < 1.0.0-rc).
/// • When both versions have pre-release labels, they are compared component
///   by component, splitting on `.`:
///   – purely numeric identifiers are compared numerically;
///   – non-numeric identifiers are compared lexically (ASCII order);
///   – numeric identifiers have *lower* precedence than non-numeric ones
///     (SemVer rule 11).
/// • Build metadata introduced by `+` (e.g. `1.0.0+20180101`) never affects
///   ordering and is ignored.
///
/// The implementation is completely self-contained – no external packages.
bool canUpgrade(String installed, String current) {
  return _Version.parse(current).compareTo(_Version.parse(installed)) > 0;
}

/* -------------------------------------------------------------------------- */
/*                            Internal implementation                          */
/* -------------------------------------------------------------------------- */

class _Version implements Comparable<_Version> {
  _Version(this.parts, this.preRelease);

  /// Dot-separated version components (numeric or string).
  final List<_VersionPart> parts;

  /// Pre-release identifiers, possibly empty.
  final List<_Identifier> preRelease;

  /* ------------------------------- parsing -------------------------------- */

  static final _coreRegex = RegExp(r'^v?', caseSensitive: false);

  static _Version parse(String input) {
    // 1. strip leading 'v' or 'V'
    input = input.replaceFirst(_coreRegex, '');

    // 2. drop build metadata (`+...`)
    final plus = input.indexOf('+');
    if (plus != -1) input = input.substring(0, plus);

    // 3. split pre-release (`-...`)
    String core;
    String? pre;
    final dash = input.indexOf('-');
    if (dash == -1) {
      core = input;
      pre = null;
    } else {
      core = input.substring(0, dash);
      pre = input.substring(dash + 1);
    }

    final parts = _parseVersionParts(core);

    final preParts = <_Identifier>[];
    if (pre != null) {
      for (final id in pre.split('.')) {
        preParts.add(_Identifier(id));
      }
    }

    return _Version(parts, preParts);
  }

  static List<_VersionPart> _parseVersionParts(String core) {
    return core
        .split('.')
        .map((part) => _VersionPart(part))
        .toList(growable: false);
  }

  /* ----------------------------- comparison ------------------------------- */

  @override
  int compareTo(_Version other) {
    // 1. compare version parts
    final maxLen = parts.length > other.parts.length
        ? parts.length
        : other.parts.length;
    for (var i = 0; i < maxLen; i++) {
      final a = i < parts.length ? parts[i] : _VersionPart.zero;
      final b = i < other.parts.length ? other.parts[i] : _VersionPart.zero;
      final cmp = a.compareTo(b);
      if (cmp != 0) return cmp;
    }

    // 2. handle pre-release vs stable
    final aHasPre = preRelease.isNotEmpty;
    final bHasPre = other.preRelease.isNotEmpty;
    if (aHasPre && !bHasPre) return -1; // pre-release < stable
    if (!aHasPre && bHasPre) return 1; // stable > pre-release

    // 3. both stable or both prerelease
    final maxPre = preRelease.length > other.preRelease.length
        ? preRelease.length
        : other.preRelease.length;

    for (var i = 0; i < maxPre; i++) {
      final aId = i < preRelease.length ? preRelease[i] : _Identifier.empty;
      final bId = i < other.preRelease.length
          ? other.preRelease[i]
          : _Identifier.empty;

      final cmp = aId.compareTo(bId);
      if (cmp != 0) return cmp;
    }

    // versions are identical
    return 0;
  }

  @override
  String toString() {
    final core = parts.join('.');
    if (preRelease.isEmpty) return core;
    return '$core-${preRelease.join('.')}';
  }
}

/* -------------------------------------------------------------------------- */

class _VersionPart implements Comparable<_VersionPart> {
  _VersionPart(String raw) : isNumeric = _numeric.hasMatch(raw), value = raw;

  static final _numeric = RegExp(r'^[0-9]+$');

  /// Special value for absent parts when lengths differ.
  static final zero = _VersionPart('0');

  final bool isNumeric;
  final String value;

  @override
  int compareTo(_VersionPart other) {
    // If both are numeric, compare numerically
    if (isNumeric && other.isNumeric) {
      return int.parse(value).compareTo(int.parse(other.value));
    }

    // If both are non-numeric, compare lexically
    if (!isNumeric && !other.isNumeric) {
      return value.compareTo(other.value);
    }

    // Mixed: numeric parts are considered "less than" non-numeric parts
    // This means "1.0.0" < "android.1.0"
    return isNumeric ? -1 : 1;
  }

  @override
  String toString() => value;
}

class _Identifier implements Comparable<_Identifier> {
  _Identifier(String raw) : isNumeric = _numeric.hasMatch(raw), value = raw;

  static final _numeric = RegExp(r'^[0-9]+$');

  /// Special value for absent identifiers when lengths differ.
  static final empty = _Identifier('').._isEmpty = true;

  final bool isNumeric;
  final String value;
  bool _isEmpty = false;

  @override
  int compareTo(_Identifier other) {
    // Empty identifiers are considered lower
    if (_isEmpty && other._isEmpty) return 0;
    if (_isEmpty) return -1;
    if (other._isEmpty) return 1;

    // Numeric vs non-numeric
    if (isNumeric && other.isNumeric) {
      // numeric compare
      return int.parse(value).compareTo(int.parse(other.value));
    }
    if (isNumeric != other.isNumeric) {
      // numeric identifiers have lower precedence than non-numeric
      return isNumeric ? -1 : 1;
    }
    // Both non-numeric: lexicographic ASCII
    return value.compareTo(other.value);
  }

  @override
  String toString() => value;
}
