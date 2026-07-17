/// Whether [id] is a Nostr addressable app coordinate (`kind:pubkey:d`).
///
/// Private stacks may store either these coordinates (e.g. Saved Apps) or bare
/// Android package IDs (e.g. Unmanaged Apps).
bool isAddressableAppId(String id) {
  final parts = id.split(':');
  return parts.length >= 3 && int.tryParse(parts.first) != null;
}

/// Split stack member IDs into addressable coordinates vs bare package IDs.
({List<String> addressableIds, List<String> packageIds}) partitionStackAppIds(
  Iterable<String> ids,
) {
  final addressableIds = <String>[];
  final packageIds = <String>[];
  for (final id in ids) {
    if (isAddressableAppId(id)) {
      addressableIds.add(id);
    } else if (id.isNotEmpty) {
      packageIds.add(id);
    }
  }
  return (addressableIds: addressableIds, packageIds: packageIds);
}

/// How a single stack member ID should be rendered.
enum StackAppResolveKind {
  /// Matched a catalog [App] via addressable ID (`app.id`).
  catalogAddressable,

  /// Matched a catalog [App] via bare package ID (`app.identifier`).
  catalogPackage,

  /// No catalog match — render installed/unknown package metadata.
  packageFallback,
}

class StackAppResolution {
  const StackAppResolution({required this.rawId, required this.kind});

  final String rawId;
  final StackAppResolveKind kind;
}

/// Resolve ordered stack IDs into display strategies.
///
/// Catalog matches win. Unmatched bare package IDs still produce a
/// [StackAppResolveKind.packageFallback] entry so unmanaged apps remain visible.
List<StackAppResolution> resolveStackAppIds({
  required Iterable<String> orderedIds,
  required Set<String> foundAddressableIds,
  required Set<String> foundPackageIds,
}) {
  final resolutions = <StackAppResolution>[];
  for (final id in orderedIds) {
    if (isAddressableAppId(id)) {
      if (foundAddressableIds.contains(id)) {
        resolutions.add(
          StackAppResolution(
            rawId: id,
            kind: StackAppResolveKind.catalogAddressable,
          ),
        );
      }
      continue;
    }

    if (foundPackageIds.contains(id)) {
      resolutions.add(
        StackAppResolution(rawId: id, kind: StackAppResolveKind.catalogPackage),
      );
      continue;
    }

    resolutions.add(
      StackAppResolution(rawId: id, kind: StackAppResolveKind.packageFallback),
    );
  }
  return resolutions;
}
