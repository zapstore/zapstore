import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/services/attestations/attestation_models.dart';
import 'package:zapstore/services/attestations/attestation_parser.dart';

const _attester =
    '78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d';
const _attestee =
    '6cdebccabda1dfa058ab85352a79509b592b2bdfa0370325e28ec1cb4f18667d';
const _other =
    '460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c';

void main() {
  group('parseServiceAttestation', () {
    test('returns null when p tag missing', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [['d', 'review-1']],
        content: '',
      );
      expect(result, isNull);
    });

    test('returns null when d tag missing', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [['p', _attestee]],
        content: '',
      );
      expect(result, isNull);
    });

    test('returns null when p tag value is not 64-hex', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [['p', 'not-a-pubkey'], ['d', 'r-1']],
        content: '',
      );
      expect(result, isNull);
    });

    test('parses a minimal valid attestation', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [
          ['p', _attestee],
          ['d', 'review-1'],
        ],
        content: '',
      );
      expect(result, isNotNull);
      expect(result!.attesterPubkey, _attester);
      expect(result.attesteePubkey, _attestee);
      expect(result.dTag, 'review-1');
      expect(result.normalisedRating, isNull);
      expect(result.namecoinAnchor, isNull);
    });

    test('parses rating into [0,1]', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [
          ['p', _attestee],
          ['d', 'r-1'],
          ['rating', '4.5', '5'],
        ],
        content: '',
      );
      expect(result!.normalisedRating, closeTo(0.9, 1e-9));
      expect(result.ratingScore, 4.5);
      expect(result.ratingMax, 5);
    });

    test('handles binary rating 1/1', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [
          ['p', _attestee],
          ['d', 'r-1'],
          ['rating', '1', '1'],
        ],
        content: '',
      );
      expect(result!.normalisedRating, 1.0);
    });

    test('clamps rating > max to 1.0', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [
          ['p', _attestee],
          ['d', 'r-1'],
          ['rating', '15', '5'],
        ],
        content: '',
      );
      expect(result!.normalisedRating, 1.0);
    });

    test('drops malformed rating', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [
          ['p', _attestee],
          ['d', 'r-1'],
          ['rating', 'nope', 'also-nope'],
        ],
        content: '',
      );
      expect(result!.normalisedRating, isNull);
    });

    test('parses nmc anchor', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [
          ['p', _attestee],
          ['d', 'r-1'],
          ['nmc', 'd/example', '825000'],
        ],
        content: '',
      );
      expect(result!.namecoinAnchor, isNotNull);
      expect(result.namecoinAnchor!.name, 'd/example');
      expect(result.namecoinAnchor!.blockHeight, 825000);
    });

    test('drops nmc anchor with malformed height', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [
          ['p', _attestee],
          ['d', 'r-1'],
          ['nmc', 'd/example', 'not-a-height'],
        ],
        content: '',
      );
      expect(result!.namecoinAnchor, isNull);
    });

    test('completed_at overrides created_at', () {
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000100,
        tags: [
          ['p', _attestee],
          ['d', 'r-1'],
          ['completed_at', '1699999000'],
        ],
        content: '',
      );
      expect(
        result!.completedAt.millisecondsSinceEpoch ~/ 1000,
        1699999000,
      );
      expect(
        result.createdAt.millisecondsSinceEpoch ~/ 1000,
        1700000100,
      );
    });

    test('ignores unknown tags', () {
      // INVARIANTS.md: parsing unknown/future tags must not crash.
      final result = parseServiceAttestation(
        attesterPubkey: _attester,
        createdAtSec: 1700000000,
        tags: [
          ['p', _attestee],
          ['d', 'r-1'],
          ['future-tag', 'value'],
          ['L', 'nip-N4.service'],
          ['l', 'software', 'nip-N4.service'],
        ],
        content: '',
      );
      expect(result, isNotNull);
    });
  });

  group('dedupeAttestations', () {
    ServiceAttestation make({
      required String attester,
      required String dTag,
      required int createdSec,
    }) {
      return parseServiceAttestation(
        attesterPubkey: attester,
        createdAtSec: createdSec,
        tags: [['p', _attestee], ['d', dTag]],
        content: '',
      )!;
    }

    test('newest wins per (attester, dTag)', () {
      final out = dedupeAttestations([
        make(attester: _attester, dTag: 'r-1', createdSec: 1000),
        make(attester: _attester, dTag: 'r-1', createdSec: 2000),
        make(attester: _attester, dTag: 'r-1', createdSec: 1500),
      ]);
      expect(out.length, 1);
      expect(out.first.createdAt.millisecondsSinceEpoch ~/ 1000, 2000);
    });

    test('different d-tags coexist', () {
      final out = dedupeAttestations([
        make(attester: _attester, dTag: 'r-1', createdSec: 1000),
        make(attester: _attester, dTag: 'r-2', createdSec: 1500),
      ]);
      expect(out.length, 2);
    });

    test('different attesters coexist', () {
      final out = dedupeAttestations([
        make(attester: _attester, dTag: 'r-1', createdSec: 1000),
        make(attester: _other, dTag: 'r-1', createdSec: 1500),
      ]);
      expect(out.length, 2);
    });

    test('orders newest first', () {
      final out = dedupeAttestations([
        make(attester: _attester, dTag: 'r-1', createdSec: 1000),
        make(attester: _other, dTag: 'r-1', createdSec: 3000),
        make(attester: _attester, dTag: 'r-2', createdSec: 2000),
      ]);
      expect(
        out.map((a) => a.createdAt.millisecondsSinceEpoch ~/ 1000),
        [3000, 2000, 1000],
      );
    });
  });

  group('AttestationSummary', () {
    test('counts and averages', () {
      final atts = dedupeAttestations([
        parseServiceAttestation(
          attesterPubkey: _attester,
          createdAtSec: 1000,
          tags: [
            ['p', _attestee],
            ['d', 'r-1'],
            ['rating', '5', '5'],
          ],
          content: '',
        )!,
        parseServiceAttestation(
          attesterPubkey: _other,
          createdAtSec: 1500,
          tags: [
            ['p', _attestee],
            ['d', 'r-1'],
            ['rating', '3', '5'],
            ['nmc', 'd/example', '825000'],
          ],
          content: '',
        )!,
      ]);
      final summary = AttestationSummary(
        attesteePubkey: _attestee,
        attestations: atts,
      );
      expect(summary.attestationCount, 2);
      expect(summary.attesterCount, 2);
      expect(summary.averageRating, closeTo(0.8, 1e-9));
      expect(summary.namecoinAnchoredCount, 1);
    });

    test('averageRating is null when no ratings parseable', () {
      final atts = dedupeAttestations([
        parseServiceAttestation(
          attesterPubkey: _attester,
          createdAtSec: 1000,
          tags: [['p', _attestee], ['d', 'r-1']],
          content: '',
        )!,
      ]);
      final summary = AttestationSummary(
        attesteePubkey: _attestee,
        attestations: atts,
      );
      expect(summary.averageRating, isNull);
    });
  });
}
