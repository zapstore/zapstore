/// Debug utilities for development and testing
library;

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';

/// Check if the current user is in debug mode based on their pubkey
bool isDebugMode(String? pubkey) {
  if (pubkey == null) return false;

  const allowedHexKeys = {
    '726a1e261cc6474674e8285e3951b3bb139be9a773d1acf49dc868db861a1c11',
    '227ce06aa5fb84bf70f25d887519374d1bfd3e1ffb75697bb0aa8f6396f32e43',
    '24480686b56234a240fd9827209b584847f3d4f9657f0d9a97aec5320a264acb',
  };

  // Try direct comparison first (hex format)
  if (allowedHexKeys.contains(pubkey)) {
    return true;
  }

  // Try converting from npub if needed
  try {
    if (pubkey.startsWith('npub')) {
      final decoded = Utils.decodeShareableIdentifier(pubkey);
      if (decoded is ProfileData && allowedHexKeys.contains(decoded.pubkey)) {
        return true;
      }
    }
  } catch (e) {
    // Failed to decode npub
  }

  return false;
}

/// Provider that exposes a Ref for use in functions that need consistent Ref type
/// Usage: ref.read(refProvider) to get a Ref from WidgetRef context
final refProvider = Provider<Ref>((ref) => ref);
