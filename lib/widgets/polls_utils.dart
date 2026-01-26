import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:models/models.dart';

/// Zapstore team npubs authorized to create polls on any app
const zapstoreTeamNpubs = {
  'npub10r8xl2njyepcw2zwv3a6dyufj4e4ajx86hz6v4ehu4gnpupxxp7stjt2p8',
  'npub1wf4pufsucer5va8g9p0rj5dnhvfeh6d8w0g6eayaep5dhps6rsgs43dgh9',
  'npub1zafcms4xya5ap9zr7xxr0jlrtrattwlesytn2s42030lzu0dwlzqpd26k5',
};

/// Convert zapstore team npubs to hex pubkeys
@visibleForTesting
Set<String> get zapstoreTeamHex => zapstoreTeamNpubs.map((npub) {
      try {
        return Utils.decodeShareableToString(npub);
      } catch (_) {
        return npub;
      }
    }).toSet();

/// Check if a pubkey is authorized to create polls on an app
///
/// Returns true if:
/// - signedInPubkey matches appPubkey (app developer)
/// - signedInPubkey is a zapstore team member
@visibleForTesting
bool canCreatePoll(String? signedInPubkey, String? appPubkey) {
  if (signedInPubkey == null) return false;
  // App developer can create polls on their own app
  if (signedInPubkey == appPubkey) return true;
  // Zapstore team can create polls on any app
  return zapstoreTeamHex.contains(signedInPubkey);
}

/// Filter out votes created after poll expiry (per NIP-88)
/// Votes at exactly the end time are included
@visibleForTesting
List<T> filterValidResponses<T>({
  required List<T> responses,
  required DateTime? pollEndsAt,
  required DateTime Function(T) getCreatedAt,
}) {
  if (pollEndsAt == null) return responses;
  // !isAfter means "at or before" - includes votes at exact end time
  return responses.where((r) => !getCreatedAt(r).isAfter(pollEndsAt)).toList();
}

/// Calculate vote counts per option (NIP-88 compliant)
///
/// For singlechoice: only count first response tag
/// For multiplechoice: count all response tags
/// Returns (voteCounts, validVoterCount) - validVoterCount excludes invalid votes
@visibleForTesting
({Map<String, int> counts, int validVoterCount}) calculateVoteCounts<T>({
  required Set<String> validOptionIds,
  required List<T> responses,
  required bool isSingleChoice,
  required String? Function(T) getFirstOptionId,
  required Iterable<String> Function(T) getAllOptionIds,
}) {
  final voteCounts = <String, int>{};
  for (final optionId in validOptionIds) {
    voteCounts[optionId] = 0;
  }

  int validVoterCount = 0;
  for (final response in responses) {
    // For singlechoice: only first option counts (per NIP-88)
    final optionIds = isSingleChoice
        ? [getFirstOptionId(response)].whereType<String>()
        : getAllOptionIds(response);

    bool hasValidVote = false;
    for (final optionId in optionIds) {
      if (validOptionIds.contains(optionId)) {
        voteCounts[optionId] = (voteCounts[optionId] ?? 0) + 1;
        hasValidVote = true;
      }
    }
    if (hasValidVote) validVoterCount++;
  }

  return (counts: voteCounts, validVoterCount: validVoterCount);
}
