import 'dart:math';

int compareVersions(String installedVersion, String latestFromRemoteVersion) {
  // Split the versions into main and pre-release components
  final parts1 = installedVersion.split('-');
  final parts2 = latestFromRemoteVersion.split('-');

  // Split the main version into numeric parts
  final mainVersion1 = parts1[0].split('.').map(int.tryParse).toList();
  final mainVersion2 = parts2[0].split('.').map(int.tryParse).toList();

  // Compare the main version parts
  for (var i = 0; i < max(mainVersion1.length, mainVersion2.length); i++) {
    final v1 = i < mainVersion1.length ? (mainVersion1[i] ?? 0) : 0;
    final v2 = i < mainVersion2.length ? (mainVersion2[i] ?? 0) : 0;

    if (v1 < v2) return 1; // upgrade
    if (v1 > v2) return -1; // downgrade
  }

  // If main versions are equal, compare pre-release versions if they exist
  final preRelease1 = parts1.length > 1 ? parts1[1] : '';
  final preRelease2 = parts2.length > 1 ? parts2[1] : '';

  if (preRelease1.isEmpty && preRelease2.isNotEmpty) {
    return -1; // downgrade
  }
  if (preRelease2.isEmpty && preRelease1.isNotEmpty) {
    return 1; // upgrade
  }

  // Compare pre-release versions lexicographically
  final comparison = preRelease2.compareTo(preRelease1);

  return comparison < 0
      ? -1
      : comparison > 0
          ? 1
          : 0;
}
