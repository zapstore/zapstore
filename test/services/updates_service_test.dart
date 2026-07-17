import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/services/updates_service.dart';

void main() {
  const cataloged = PackageInfo(
    appId: 'dev.zapstore.cataloged',
    version: '1.0.0',
    versionCode: 1,
  );
  const other = PackageInfo(
    appId: 'com.example.other',
    version: '1.0.0',
    versionCode: 1,
  );

  test('a managed app is included in the catalog discovery scope', () {
    final ids = managedInstalledAppIds(
      {cataloged.appId: cataloged, other.appId: other},
      {other.appId},
    );

    expect(ids, {cataloged.appId});
  });

  test('unmanaging an app removes it from catalog discovery scope', () {
    final ids = managedInstalledAppIds(
      {cataloged.appId: cataloged, other.appId: other},
      {cataloged.appId, other.appId},
    );

    expect(ids, isEmpty);
  });
}
