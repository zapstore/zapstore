/// Application identifier for Zapstore itself
const kZapstoreAppIdentifier = 'dev.zapstore.alpha';

/// Zapstore's public key for relay-signed apps
const kZapstorePubkey =
    '78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d';

/// Franzap's public key for curation sets
const kFranzapPubkey =
    '726a1e261cc6474674e8285e3951b3bb139be9a773d1acf49dc868db861a1c11';

/// Identifier for storing user saved apps
const kAppBookmarksIdentifier = 'zapstore-bookmarks';

/// Event filter for app stacks - excludes saved apps and stacks with zero App references
bool appStackEventFilter(Map<String, dynamic> event) {
  final tags = event['tags'] as List<dynamic>?;
  if (tags == null) return false;

  // Check for saved apps identifier in 'd' tag
  for (final tag in tags) {
    if (tag is List && tag.isNotEmpty && tag[0] == 'd') {
      if (tag.length > 1 && tag[1] == kAppBookmarksIdentifier) {
        return false;
      }
    }
  }

  // Check for at least one 'a' tag starting with '32267:' (App kind)
  for (final tag in tags) {
    if (tag is List && tag.isNotEmpty && tag[0] == 'a') {
      if (tag.length > 1 && tag[1] is String && tag[1].startsWith('32267:')) {
        return true;
      }
    }
  }

  // No valid App references found
  return false;
}

/// Amber signer package ID
const kAmberPackageId = 'com.greenart7c3.nostrsigner';

/// Amber signer naddr
const kAmberNaddr =
    'naddr1qqdkxmmd9enhyet9deshyaphvvejumn0wd68yumfvahx2uszyp6hjpmdntls5n8aa7n7ypzlyjrv0ewch33ml3452wtjx0smhl93jqcyqqq8uzcgpp6ky';
