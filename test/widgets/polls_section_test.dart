import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';

// Zapstore team npubs (same as in polls_section.dart)
const _zapstoreTeamNpubs = {
  'npub10r8xl2njyepcw2zwv3a6dyufj4e4ajx86hz6v4ehu4gnpupxxp7stjt2p8',
  'npub1wf4pufsucer5va8g9p0rj5dnhvfeh6d8w0g6eayaep5dhps6rsgs43dgh9',
  'npub1zafcms4xya5ap9zr7xxr0jlrtrattwlesytn2s42030lzu0dwlzqpd26k5',
};

// Convert zapstore team npubs to hex (for testing)
Set<String> get _zapstoreTeamHex => _zapstoreTeamNpubs.map((npub) {
      try {
        return Utils.decodeShareableToString(npub);
      } catch (_) {
        return npub;
      }
    }).toSet();

/// Check if a pubkey is authorized to create polls on an app
/// (extracted from polls_section.dart for testing)
bool canCreatePoll(String? signedInPubkey, String appPubkey) {
  if (signedInPubkey == null) return false;
  // App developer can create polls on their own app
  if (signedInPubkey == appPubkey) return true;
  // Zapstore team can create polls on any app
  return _zapstoreTeamHex.contains(signedInPubkey);
}

/// Deduplicate responses by pubkey (latest wins)
/// (extracted from polls_section.dart for testing)
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

/// Calculate vote counts per option
Map<String, int> calculateVoteCounts(
  List<String> optionIds,
  List<MockPollResponse> responses,
) {
  final voteCounts = <String, int>{};
  for (final optionId in optionIds) {
    voteCounts[optionId] = 0;
  }
  for (final response in responses) {
    for (final optionId in response.selectedOptionIds) {
      voteCounts[optionId] = (voteCounts[optionId] ?? 0) + 1;
    }
  }
  return voteCounts;
}

/// Mock poll response for testing
class MockPollResponse {
  final String pubkey;
  final DateTime createdAt;
  final Set<String> selectedOptionIds;

  MockPollResponse({
    required this.pubkey,
    required this.createdAt,
    required this.selectedOptionIds,
  });
}

void main() {
  group('canCreatePoll', () {
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
      // First zapstore team member (converted from npub)
      final zapstoreTeamMember = _zapstoreTeamHex.first;
      expect(canCreatePoll(zapstoreTeamMember, appDevPubkey), isTrue);
    });

    test('npub to hex conversion works correctly', () {
      // Verify that all npubs convert to valid 64-char hex strings
      for (final hex in _zapstoreTeamHex) {
        expect(hex.length, equals(64));
        expect(RegExp(r'^[0-9a-f]+$').hasMatch(hex), isTrue);
      }
    });
  });

  group('deduplicateResponses', () {
    test('keeps single response per pubkey', () {
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 10, 0),
          selectedOptionIds: {'opt0'},
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime(2026, 1, 26, 10, 0),
          selectedOptionIds: {'opt1'},
        ),
      ];

      final result = deduplicateResponses(responses);
      expect(result.length, equals(2));
      expect(result['user1']!.selectedOptionIds, equals({'opt0'}));
      expect(result['user2']!.selectedOptionIds, equals({'opt1'}));
    });

    test('keeps latest response when user votes multiple times', () {
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 10, 0),
          selectedOptionIds: {'opt0'},
        ),
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime(2026, 1, 26, 11, 0), // Later
          selectedOptionIds: {'opt1'},
        ),
      ];

      final result = deduplicateResponses(responses);
      expect(result.length, equals(1));
      expect(result['user1']!.selectedOptionIds, equals({'opt1'}));
    });

    test('handles empty list', () {
      final result = deduplicateResponses([]);
      expect(result.isEmpty, isTrue);
    });
  });

  group('calculateVoteCounts', () {
    test('counts votes correctly for single-choice poll', () {
      final optionIds = ['opt0', 'opt1', 'opt2'];
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime.now(),
          selectedOptionIds: {'opt0'},
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime.now(),
          selectedOptionIds: {'opt0'},
        ),
        MockPollResponse(
          pubkey: 'user3',
          createdAt: DateTime.now(),
          selectedOptionIds: {'opt1'},
        ),
      ];

      final counts = calculateVoteCounts(optionIds, responses);
      expect(counts['opt0'], equals(2));
      expect(counts['opt1'], equals(1));
      expect(counts['opt2'], equals(0));
    });

    test('counts votes correctly for multi-choice poll', () {
      final optionIds = ['opt0', 'opt1', 'opt2'];
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime.now(),
          selectedOptionIds: {'opt0', 'opt1'}, // Voted for two options
        ),
        MockPollResponse(
          pubkey: 'user2',
          createdAt: DateTime.now(),
          selectedOptionIds: {'opt1', 'opt2'},
        ),
      ];

      final counts = calculateVoteCounts(optionIds, responses);
      expect(counts['opt0'], equals(1));
      expect(counts['opt1'], equals(2));
      expect(counts['opt2'], equals(1));
    });

    test('returns zeros for poll with no votes', () {
      final optionIds = ['opt0', 'opt1'];
      final counts = calculateVoteCounts(optionIds, []);
      expect(counts['opt0'], equals(0));
      expect(counts['opt1'], equals(0));
    });

    test('counts votes for unknown options but they are not displayed', () {
      // Unknown options get counted but since we only iterate over
      // known optionIds when displaying, they are effectively ignored
      final optionIds = ['opt0', 'opt1'];
      final responses = [
        MockPollResponse(
          pubkey: 'user1',
          createdAt: DateTime.now(),
          selectedOptionIds: {'opt0', 'unknown_option'},
        ),
      ];

      final counts = calculateVoteCounts(optionIds, responses);
      expect(counts['opt0'], equals(1));
      expect(counts['opt1'], equals(0));
      // Unknown option gets counted but won't be displayed in UI
      // since we iterate over poll.options, not voteCounts keys
      expect(counts['unknown_option'], equals(1));
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
}
