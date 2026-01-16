import 'dart:convert';
import 'dart:math';

import 'package:bip340/bip340.dart' as bip340;
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:models/models.dart';

/// NIP-17 Gift Wrap service for private crash report delivery.
///
/// Implements the NIP-59 gift wrap protocol:
/// 1. Rumor (kind 14) - unsigned message
/// 2. Seal (kind 13) - encrypted rumor, signed by sender
/// 3. Gift Wrap (kind 1059) - encrypted seal, signed by ephemeral key
///
/// This provides enhanced privacy by hiding the sender's identity
/// through an ephemeral wrapper key.
class Nip17GiftWrapService {
  Nip17GiftWrapService(this.ref);

  final Ref ref;

  static const _twoDaysInSeconds = 2 * 24 * 60 * 60;

  /// Create and publish a NIP-17 gift-wrapped message.
  ///
  /// [content] - The message content
  /// [recipientPubkey] - Recipient's public key (hex)
  /// [expirationDays] - Optional expiration in days (NIP-40)
  Future<void> sendGiftWrappedMessage({
    required String content,
    required String recipientPubkey,
    int? expirationDays,
  }) async {
    // Create ephemeral sender signer for the seal
    final senderPrivateKey = Utils.generateRandomHex64();
    final senderSigner = Bip340PrivateKeySigner(senderPrivateKey, ref);
    await senderSigner.signIn(setAsActive: false, registerSigner: false);
    final senderPubkey = senderSigner.pubkey;

    // Create ephemeral signer for gift wrap
    final ephemeralPrivateKey = Utils.generateRandomHex64();
    final ephemeralSigner = Bip340PrivateKeySigner(ephemeralPrivateKey, ref);
    await ephemeralSigner.signIn(setAsActive: false, registerSigner: false);
    final ephemeralPubkey = ephemeralSigner.pubkey;

    // 1. Create rumor (kind 14, unsigned)
    // NIP-17 specifies the rumor should have sig: '' (empty string)
    final rumorCreatedAt = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final rumorTags = <List<String>>[
      ['p', recipientPubkey],
    ];
    final rumorId = _computeEventIdFromParts(
      senderPubkey,
      rumorCreatedAt,
      14,
      rumorTags,
      content,
    );

    // Create rumor with proper field order matching NIP-01 event structure
    final rumor = <String, dynamic>{
      'id': rumorId,
      'pubkey': senderPubkey,
      'created_at': rumorCreatedAt,
      'kind': 14,
      'tags': rumorTags,
      'content': content,
      'sig': '', // NIP-17: rumor's sig is empty string
    };

    final rumorJson = jsonEncode(rumor);

    // 2. Create seal (kind 13)
    // Encrypt the rumor JSON with NIP-44 to the recipient
    final encryptedRumor = await senderSigner.nip44Encrypt(
      rumorJson,
      recipientPubkey,
    );

    final sealCreatedAt = _randomizeTimestamp(rumorCreatedAt);
    final sealTags = <List<String>>[];
    final sealId = _computeEventIdFromParts(
      senderPubkey,
      sealCreatedAt,
      13,
      sealTags,
      encryptedRumor,
    );
    final sealSig = await _signEventId(senderPrivateKey, sealId);

    // Create seal with proper field order
    final signedSeal = <String, dynamic>{
      'id': sealId,
      'pubkey': senderPubkey,
      'created_at': sealCreatedAt,
      'kind': 13,
      'tags': sealTags,
      'content': encryptedRumor,
      'sig': sealSig,
    };
    final sealJson = jsonEncode(signedSeal);

    // 3. Create gift wrap (kind 1059)
    // Encrypt the seal JSON with NIP-44 using ephemeral key
    final encryptedSeal = await ephemeralSigner.nip44Encrypt(
      sealJson,
      recipientPubkey,
    );

    final giftWrapCreatedAt = _randomizeTimestamp(rumorCreatedAt);
    final giftWrapTags = <List<String>>[
      ['p', recipientPubkey],
    ];

    // Add expiration to gift wrap if specified (NIP-40)
    if (expirationDays != null) {
      final expiration = DateTime.now()
              .add(Duration(days: expirationDays))
              .millisecondsSinceEpoch ~/
          1000;
      giftWrapTags.add(['expiration', expiration.toString()]);
    }

    final giftWrapId = _computeEventIdFromParts(
      ephemeralPubkey,
      giftWrapCreatedAt,
      1059,
      giftWrapTags,
      encryptedSeal,
    );
    final giftWrapSig = await _signEventId(ephemeralPrivateKey, giftWrapId);

    // Create gift wrap with proper field order
    final signedGiftWrap = <String, dynamic>{
      'id': giftWrapId,
      'pubkey': ephemeralPubkey,
      'created_at': giftWrapCreatedAt,
      'kind': 1059,
      'tags': giftWrapTags,
      'content': encryptedSeal,
      'sig': giftWrapSig,
    };

    // Debug: Print the gift wrap event structure
    if (kDebugMode) {
      debugPrint('=== NIP-17 Gift Wrap Debug ===');
      debugPrint('Rumor (kind 14): ${jsonEncode(rumor)}');
      debugPrint('Seal (kind 13): ${jsonEncode(signedSeal)}');
      debugPrint('Gift Wrap (kind 1059): ${jsonEncode(signedGiftWrap)}');
    }

    // Publish using RawGiftWrap wrapper
    final rawEvent = RawGiftWrap.fromMap(signedGiftWrap, ref);

    // Debug: Print what toMap returns
    if (kDebugMode) {
      debugPrint('RawGiftWrap.toMap(): ${jsonEncode(rawEvent.toMap())}');
      debugPrint('RawGiftWrap.event.kind: ${rawEvent.event.kind}');
    }

    await ref.read(storageNotifierProvider.notifier).publish(
      {rawEvent},
      source: const RemoteSource(relays: 'social'),
    );
  }

  /// Compute the event ID (sha256 of serialized event data).
  /// Matches NIP-01 serialization: [0, pubkey, created_at, kind, tags, content]
  String _computeEventIdFromParts(
    String pubkey,
    int createdAt,
    int kind,
    List<List<String>> tags,
    String content,
  ) {
    // NIP-01: [0, <pubkey lowercase hex>, <created_at>, <kind>, <tags>, <content>]
    final eventData = [
      0,
      pubkey.toLowerCase(),
      createdAt,
      kind,
      tags,
      content,
    ];
    final serialized = jsonEncode(eventData);
    final hashBytes = sha256.convert(utf8.encode(serialized));
    // Return lowercase hex string (matching hex.encode behavior)
    return hashBytes.bytes
        .map((b) => b.toRadixString(16).padLeft(2, '0'))
        .join();
  }

  /// Sign an event ID using BIP-340 Schnorr signature.
  Future<String> _signEventId(String privateKeyHex, String eventId) async {
    // Generate 32 random bytes for aux (matches 0xchat's generate64RandomHexChars)
    final random = Random.secure();
    final auxBytes =
        List.generate(32, (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'));
    final aux = auxBytes.join();
    return bip340.sign(privateKeyHex, eventId, aux);
  }

  /// Randomize timestamp by subtracting 0-2 days for privacy.
  int _randomizeTimestamp(int baseTimestamp) {
    final random = Random.secure();
    final offset = random.nextInt(_twoDaysInSeconds);
    return baseTimestamp - offset;
  }
}

/// Raw gift wrap event wrapper for publishing.
///
/// This extends Note (kind 1) but overrides toMap to output kind 1059.
/// This is a workaround to publish raw events through the models framework.
class RawGiftWrap extends Note {
  RawGiftWrap.fromMap(super.map, super.ref) : super.fromMap();

  @override
  Map<String, dynamic> toMap() {
    // Return the raw event data as-is
    return event.toMap();
  }
}

final nip17GiftWrapServiceProvider = Provider<Nip17GiftWrapService>(
  Nip17GiftWrapService.new,
);
