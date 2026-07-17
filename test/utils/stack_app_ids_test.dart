import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/utils/stack_app_ids.dart';

void main() {
  group('isAddressableAppId', () {
    test('accepts kind:pubkey:identifier coordinates', () {
      expect(
        isAddressableAppId('32267:${'a' * 64}:com.example.app'),
        isTrue,
      );
    });

    test('rejects bare package IDs used by unmanaged apps', () {
      expect(isAddressableAppId('com.example.app'), isFalse);
      expect(isAddressableAppId('dev.zapstore.app'), isFalse);
    });
  });

  group('partitionStackAppIds', () {
    test('splits mixed stacks into addressable and package IDs', () {
      final addressable = '32267:${'b' * 64}:com.cataloged.app';
      final partitioned = partitionStackAppIds([
        addressable,
        'com.uncataloged.app',
        '',
      ]);

      expect(partitioned.addressableIds, [addressable]);
      expect(partitioned.packageIds, ['com.uncataloged.app']);
    });
  });

  group('resolveStackAppIds', () {
    test('unmanaged bare package IDs fall back when not in catalog', () {
      const packageId = 'com.example.unmanaged';
      final resolutions = resolveStackAppIds(
        orderedIds: [packageId],
        foundAddressableIds: const {},
        foundPackageIds: const {},
      );

      expect(resolutions, hasLength(1));
      expect(resolutions.single.rawId, packageId);
      expect(resolutions.single.kind, StackAppResolveKind.packageFallback);
    });

    test('cataloged package IDs prefer catalog match over fallback', () {
      const packageId = 'com.example.cataloged';
      final resolutions = resolveStackAppIds(
        orderedIds: [packageId],
        foundAddressableIds: const {},
        foundPackageIds: {packageId},
      );

      expect(resolutions.single.kind, StackAppResolveKind.catalogPackage);
    });

    test('addressable IDs resolve when present in catalog', () {
      final addressable = '32267:${'d' * 64}:com.saved.app';
      final resolutions = resolveStackAppIds(
        orderedIds: [addressable],
        foundAddressableIds: {addressable},
        foundPackageIds: const {},
      );

      expect(resolutions, hasLength(1));
      expect(resolutions.single.kind, StackAppResolveKind.catalogAddressable);
    });

    test('addressable IDs without catalog match are omitted', () {
      final addressable = '32267:${'d' * 64}:com.missing.app';
      final resolutions = resolveStackAppIds(
        orderedIds: [addressable],
        foundAddressableIds: const {},
        foundPackageIds: const {},
      );

      expect(resolutions, isEmpty);
    });

    test('preserves stack order across cataloged and package entries', () {
      const first = 'com.first';
      const second = 'com.second';
      final resolutions = resolveStackAppIds(
        orderedIds: [first, second],
        foundAddressableIds: const {},
        foundPackageIds: {second},
      );

      expect(resolutions.map((r) => r.rawId), [first, second]);
      expect(resolutions[0].kind, StackAppResolveKind.packageFallback);
      expect(resolutions[1].kind, StackAppResolveKind.catalogPackage);
    });

    test(
      'regression: bare unmanaged package IDs still produce visible entries',
      () {
        // Old stack-screen logic only decomposed kind:pubkey:d IDs, so bare
        // package IDs produced empty author/identifier filters and no rows.
        const packageId = 'org.thoughtcrime.securesms';
        final partitioned = partitionStackAppIds([packageId]);
        expect(partitioned.addressableIds, isEmpty);
        expect(partitioned.packageIds, [packageId]);

        final resolutions = resolveStackAppIds(
          orderedIds: [packageId],
          foundAddressableIds: const {},
          foundPackageIds: const {},
        );
        expect(resolutions, hasLength(1));
        expect(resolutions.single.kind, StackAppResolveKind.packageFallback);
      },
    );
  });
}
