import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/attestations/attestation_models.dart';
import 'package:zapstore/services/attestations/attestation_parser.dart';
import 'package:zapstore/services/log_service.dart';

/// NIP-N4 kind for a single service attestation.
const int kServiceAttestationKind = 38383;

/// NIP-N4 kind for an attester's curated set of active attestations.
const int kAttestationSetKind = 38384;

/// Read-only query service for NIP-N4 service attestations
/// (`kind:38383` / `kind:38384`).
///
/// **Experimental**: NIP-N4 is draft-only with no production
/// producer yet. Callers MUST clearly label any UI surface as
/// experimental and MUST NOT use the returned data to influence
/// whitelisting, blocking, or install decisions \u2014 per the
/// proposal in zapstore#362.
///
/// All queries are local-only by default (`LocalSource`) so the
/// service does not generate background network traffic when the
/// feature is unused. Callers that want fresh data may pass a
/// different source.
class AttestationQueryService {
  const AttestationQueryService(this.ref);

  final Ref ref;

  /// Returns the attestation summary for [attesteePubkey], collapsed
  /// to the most recent attestation per `(attesterPubkey, dTag)`.
  ///
  /// Returns an empty summary on any error \u2014 the experimental
  /// status of this kind means we never want a parser bug to
  /// surface as a crash.
  Future<AttestationSummary> summaryFor(String attesteePubkey) async {
    try {
      final storage = ref.read(storageNotifierProvider.notifier);
      final result = await storage.query(
        Request<Model<dynamic>>([
          RequestFilter<Model<dynamic>>(
            kinds: {kServiceAttestationKind},
            tags: {'#p': {attesteePubkey}},
          ),
        ]),
        source: const LocalSource(),
      );

      final parsed = <ServiceAttestation>[];
      for (final m in result) {
        final att = parseServiceAttestation(
          attesterPubkey: m.event.pubkey,
          createdAtSec: m.event.createdAt.millisecondsSinceEpoch ~/ 1000,
          tags: m.event.tags,
          content: m.event.content,
        );
        if (att != null && att.attesteePubkey == attesteePubkey) {
          parsed.add(att);
        }
      }

      return AttestationSummary(
        attesteePubkey: attesteePubkey,
        attestations: dedupeAttestations(parsed),
      );
    } on Exception catch (e, st) {
      LogService.I.warn(
        'attestation query failed',
        tag: 'attestations',
        err: e,
        stack: st,
        fields: {'attestee': attesteePubkey},
      );
      return AttestationSummary(
        attesteePubkey: attesteePubkey,
        attestations: const [],
      );
    }
  }
}

/// Riverpod provider exposing a single shared
/// [AttestationQueryService].
final attestationQueryServiceProvider =
    Provider<AttestationQueryService>(
  (ref) => AttestationQueryService(ref),
);

/// Family provider exposing the [AttestationSummary] for a given
/// developer / attestee pubkey. Watch this from a widget to
/// render the experimental "trust signal" surface.
final attestationSummaryProvider = FutureProvider.autoDispose
    .family<AttestationSummary, String>((ref, pubkey) async {
  final service = ref.watch(attestationQueryServiceProvider);
  return service.summaryFor(pubkey);
});
