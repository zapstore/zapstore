/// Application identifier for Zapstore itself
const kZapstoreAppIdentifier = 'dev.zapstore.app';

/// Zapstore's public key for relay-signed apps
const kZapstorePubkey =
    '78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d';

/// Additional trusted relay pubkeys for relay-signed apps
const kTrustedRelayPubkeys = {
  kZapstorePubkey,
  'fd2d438d5c0b419179b67ce4e8ae72a170aeca3045acccbe7661a5e1b5f0b7b1',
};

/// Franzap's public key for curation sets
const kFranzapPubkey =
    '726a1e261cc6474674e8285e3951b3bb139be9a773d1acf49dc868db861a1c11';

/// Zapstore community public key (npub14nl2afh9zsswsp5043zxe2w304afaa496gxe8z2w2rlw84ys92zqlnjx5u)
const kZapstoreCommunityPubkey =
    'acfeaea6e51420e8068fac446ca9d17d7a9ef6a5d20d93894e50fee3d4902a84';

/// Identifier for storing user saved apps
const kAppBookmarksIdentifier = 'zapstore-bookmarks';

/// Identifier for the encrypted stack of installed apps
const kInstalledAppsIdentifier = 'zapstore-installed-apps';

/// Identifier for the encrypted stack of apps the user chose as unmanaged
const kUnmanagedAppsIdentifier = 'zapstore-unmanaged-apps';


/// Authoritative relay for NIP-82 software application events (kind 32267).
/// Used as the relay hint in naddr encoding so other clients can resolve app events.
/// Invariant: all Zapstore-published app events are available on this relay.
/// Stack events (social relays) should NOT use this hint.
const kDefaultRelay = 'wss://relay.zapstore.dev';

/// Amber signer package ID
const kAmberPackageId = 'com.greenart7c3.nostrsigner';

/// Amber signer naddr
const kAmberNaddr =
    'naddr1qqdkxmmd9enhyet9deshyaphvvejumn0wd68yumfvahx2uszyp6hjpmdntls5n8aa7n7ypzlyjrv0ewch33ml3452wtjx0smhl93jqcyqqq8uzcgpp6ky';
