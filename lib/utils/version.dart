import 'dart:math';

int compareVersions(String version1, String version2) {
  // Split the versions into main and pre-release components
  final parts1 = version1.split('-');
  final parts2 = version2.split('-');

  // Split the main version into numeric parts
  final mainVersion1 = parts1[0].split('.').map(int.parse).toList();
  final mainVersion2 = parts2[0].split('.').map(int.parse).toList();

  // Compare the main version parts
  for (var i = 0; i < max(mainVersion1.length, mainVersion2.length); i++) {
    final v1 = i < mainVersion1.length ? mainVersion1[i] : 0;
    final v2 = i < mainVersion2.length ? mainVersion2[i] : 0;

    if (v1 < v2) return 1; // version2 is higher
    if (v1 > v2) return -1; // version1 is higher
  }

  // If main versions are equal, compare pre-release versions if they exist
  final preRelease1 = parts1.length > 1 ? parts1[1] : '';
  final preRelease2 = parts2.length > 1 ? parts2[1] : '';

  if (preRelease1.isEmpty && preRelease2.isNotEmpty)
    return -1; // version2 is higher
  if (preRelease2.isEmpty && preRelease1.isNotEmpty)
    return 1; // version1 is higher

  if (preRelease1.isEmpty && preRelease2.isEmpty) return 0; // both are equal

  // Compare pre-release versions lexicographically
  final comparison = preRelease1.compareTo(preRelease2);

  return comparison < 0
      ? -1
      : comparison > 0
          ? 1
          : 0;
}
