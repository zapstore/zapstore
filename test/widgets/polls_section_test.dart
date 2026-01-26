import 'package:flutter_test/flutter_test.dart';
import 'package:zapstore/widgets/polls_utils.dart';

/// Mock poll response for testing (uses List to preserve order per NIP-88)
class MockPollResponse {
  final String pubkey;
  final DateTime createdAt;
  final List<String> selectedOptionIds; // List preserves order

  MockPollResponse({
    required this.pubkey,
    required this.createdAt,
    required this.selectedOptionIds,
  });

  /// First selected option (for singlechoice polls per NIP-88)
  String? get firstSelectedOptionId =>
      selectedOptionIds.isEmpty ? null : selectedOptionIds.first;
}

/// Deduplicate responses by pubkey (latest wins)
Map<String, MockPollResponse> deduplicateResponses(
    List<MockPollResponse> responses) {
  final responsesByPubkey = <String, MockPollResponse>{};
  for (final response in responses) {
    final existing = responsesByPubkey[response.pubkey];
    if (existing == null || response.createdAt.isAfter(existing.createdAt)) {
      responsesByPubkey[response.pubkey] = response;
    }
  }
  return responsesByPubkey;
}

void main() {
  group('canCreatePoll (from polls_utils)', () {
    const appDevPubkey =
        'abcd1234567890abcd1234567890abcd1234567890abcd1234567890abcd1234';
    const randomUserPubkey =
        '1111222233334444555566667777888899990000aaaabbbbccccddddeeee0000';

    test('returns false when not signed in', () {
      expect(canCreatePoll(null, appDevPubkey), isFalse);
    });

    test('returns true when signed in as app developer', () {
      expect(canCreatePoll(appDevPubkey, appDevPubkey), isTrue);
    });

    test('returns false for random user on someone else\'s app', () {
      expect(canCreatePoll(randomUserPubkey, appDevPubkey), isFalse);
    });

    test('returns true for zapstore team member on any app', () {
      final zapstoreTeamMember = zapstoreTeamHex.first;
      expect(canCreatePoll(zapstoreTeamMember, appDevPubkey), isTrue);
    });

    test('npub to hex conversion works correctly', () {
      for (final hex in zapstoreTeamHex) {
        expect(hex.length, equals(64));
        expect(RegExp(r'^[0-9a-f]+$').hasMatch(hex), isTrue);
      }
    });
  });

  group('filterValidResponses (post-expiry filtering)', () {
    test('filters out votes after poll expiry', () {
      final pollEndsAt = DateTime(2026, 1, 26, 12, 0);
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 10, 0), // Before expiry - valid
          selectedOptionIds: ['opt0'],
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime(2026, 1, 26, 14, 0), // After expiry - invalid
          selectedOptionIds: ['opt1'],
        ),
        MockPollResponse(
          pubkey: 'user3',
          createdAt: DateTime(2026, 1, 26, 11, 59), // Just before - valid
          selectedOptionIds: ['opt0'],
        ),
      ];

      final valid = filterValidResponses(
        responses: responses,
        pollEndsAt: pollEndsAt,
        getCreatedAt: (r) => r.createdAt,
      );
      expect(valid.length, equals(2));
      expect(valid.map((r) => r.pubkey).toSet(), equals({'user1', 'user3'}));
    });

    test('includes votes at exact poll end time (NIP-88 compliance)', () {
      final pollEndsAt = DateTime(2026, 1, 26, 12, 0);
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 12, 0), // Exactly at end - valid
          selectedOptionIds: ['opt0'],
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime(2026, 1, 26, 12, 0, 0, 1), // Just after - invalid
          selectedOptionIds: ['opt1'],
        ),
      ];

      final valid = filterValidResponses(
        responses: responses,
        pollEndsAt: pollEndsAt,
        getCreatedAt: (r) => r.createdAt,
      );
      expect(valid.length, equals(1));
      expect(valid.first.pubkey, equals('user1'));
    });

    test('returns all responses when poll has no expiry', () {
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 10, 0),
          selectedOptionIds: ['opt0'],
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime(2099, 12, 31, 23, 59),
          selectedOptionIds: ['opt1'],
        ),
      ];

      final valid = filterValidResponses(
        responses: responses,
        pollEndsAt: null,
        getCreatedAt: (r) => r.createdAt,
      );
      expect(valid.length, equals(2));
    });
  });

  group('deduplicateResponses', () {
    test('keeps single response per pubkey', () {
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 10, 0),
          selectedOptionIds: ['opt0'],
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime(2026, 1, 26, 10, 0),
          selectedOptionIds: ['opt1'],
        ),
      ];

      final result = deduplicateResponses(responses);
      expect(result.length, equals(2));
      expect(result['user1']!.selectedOptionIds, equals(['opt0']));
      expect(result['user2']!.selectedOptionIds, equals(['opt1']));
    });

    test('keeps latest response when user votes multiple times', () {
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 10, 0),
          selectedOptionIds: ['opt0'],
        ),
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 11, 0), // Later
          selectedOptionIds: ['opt1'],
        ),
      ];

      final result = deduplicateResponses(responses);
      expect(result.length, equals(1));
      expect(result['user1']!.selectedOptionIds, equals(['opt1']));
    });

    test('handles empty list', () {
      final result = deduplicateResponses([]);
      expect(result.isEmpty, isTrue);
    });
  });

  group('calculateVoteCounts - single choice', () {
    test('only counts first response tag for singlechoice polls', () {
      final validOptionIds = {'opt0', 'opt1', 'opt2'};
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime.now(),
          // User sent multiple response tags, but only first should count
          selectedOptionIds: ['opt0', 'opt1', 'opt2'],
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime.now(),
          selectedOptionIds: ['opt1'],
        ),
      ];

      final result = calculateVoteCounts(
        validOptionIds: validOptionIds,
        responses: responses,
        isSingleChoice: true,
        getFirstOptionId: (r) => r.firstSelectedOptionId,
        getAllOptionIds: (r) => r.selectedOptionIds,
      );
      expect(result.counts['opt0'], equals(1)); // user1's first choice
      expect(result.counts['opt1'], equals(1)); // user2's choice
      expect(result.counts['opt2'], equals(0)); // ignored (not first)
      expect(result.validVoterCount, equals(2));
    });

    test('first response tag order is preserved', () {
      final validOptionIds = {'opt0', 'opt1'};
      // Different order in tags
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime.now(),
          selectedOptionIds: ['opt1', 'opt0'], // opt1 is first
        ),
      ];

      final result = calculateVoteCounts(
        validOptionIds: validOptionIds,
        responses: responses,
        isSingleChoice: true,
        getFirstOptionId: (r) => r.firstSelectedOptionId,
        getAllOptionIds: (r) => r.selectedOptionIds,
      );
      expect(result.counts['opt0'], equals(0));
      expect(result.counts['opt1'], equals(1)); // Only first tag counts
    });
  });

  group('calculateVoteCounts - multiple choice', () {
    test('counts all response tags for multiplechoice polls', () {
      final validOptionIds = {'opt0', 'opt1', 'opt2'};
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime.now(),
          selectedOptionIds: ['opt0', 'opt1'], // Both count
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime.now(),
          selectedOptionIds: ['opt1', 'opt2'],
        ),
      ];

      final result = calculateVoteCounts(
        validOptionIds: validOptionIds,
        responses: responses,
        isSingleChoice: false,
        getFirstOptionId: (r) => r.firstSelectedOptionId,
        getAllOptionIds: (r) => r.selectedOptionIds,
      );
      expect(result.counts['opt0'], equals(1));
      expect(result.counts['opt1'], equals(2));
      expect(result.counts['opt2'], equals(1));
      expect(result.validVoterCount, equals(2));
    });
  });

  group('calculateVoteCounts - invalid options', () {
    test('ignores votes for unknown option IDs', () {
      final validOptionIds = {'opt0', 'opt1'};
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime.now(),
          selectedOptionIds: ['opt0', 'invalid_option'],
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime.now(),
          selectedOptionIds: ['invalid_only'], // All invalid
        ),
      ];

      final result = calculateVoteCounts(
        validOptionIds: validOptionIds,
        responses: responses,
        isSingleChoice: false,
        getFirstOptionId: (r) => r.firstSelectedOptionId,
        getAllOptionIds: (r) => r.selectedOptionIds,
      );
      expect(result.counts['opt0'], equals(1));
      expect(result.counts['opt1'], equals(0));
      expect(result.counts.containsKey('invalid_option'), isFalse);
      // user2 has no valid votes, so not counted in validVoterCount
      expect(result.validVoterCount, equals(1));
    });

    test('percentage calculation excludes invalid voters', () {
      final validOptionIds = {'opt0', 'opt1'};
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime.now(),
          selectedOptionIds: ['opt0'],
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime.now(),
          selectedOptionIds: ['invalid'], // Invalid - not counted
        ),
      ];

      final result = calculateVoteCounts(
        validOptionIds: validOptionIds,
        responses: responses,
        isSingleChoice: true,
        getFirstOptionId: (r) => r.firstSelectedOptionId,
        getAllOptionIds: (r) => r.selectedOptionIds,
      );

      // Only 1 valid voter, so opt0 should be 100%
      expect(result.validVoterCount, equals(1));
      final percentage = result.validVoterCount > 0
          ? (result.counts['opt0']! / result.validVoterCount * 100)
          : 0.0;
      expect(percentage, equals(100.0));
    });
  });

  group('Poll expiration', () {
    test('poll is expired when endsAt is in the past', () {
      final endsAt = DateTime.now().subtract(const Duration(hours: 1));
      final isExpired = DateTime.now().isAfter(endsAt);
      expect(isExpired, isTrue);
    });

    test('poll is not expired when endsAt is in the future', () {
      final endsAt = DateTime.now().add(const Duration(days: 7));
      final isExpired = DateTime.now().isAfter(endsAt);
      expect(isExpired, isFalse);
    });

    test('poll with no endsAt is never expired', () {
      const DateTime? endsAt = null;
      final isExpired = endsAt != null && DateTime.now().isAfter(endsAt);
      expect(isExpired, isFalse);
    });
  });

  group('Response tag order preservation', () {
    test('List preserves tag order unlike Set', () {
      final response = MockPollResponse(
        pubkey: 'user1',
        createdAt: DateTime.now(),
        selectedOptionIds: ['third', 'first', 'second'],
      );

      // List preserves insertion order
      expect(response.selectedOptionIds[0], equals('third'));
      expect(response.selectedOptionIds[1], equals('first'));
      expect(response.selectedOptionIds[2], equals('second'));

      // First selected option is the first in the list
      expect(response.firstSelectedOptionId, equals('third'));
    });
  });
}
