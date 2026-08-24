import 'package:collection/collection.dart';
import 'package:models/models.dart';

/// The only host APKs are fetched from directly.
const kZapstoreCdnHost = 'cdn.zapstore.dev';

final _apkHashPattern = RegExp(r'^[0-9a-fA-F]{64}$');

/// Whether [hash] is a SHA-256 digest in hexadecimal form.
///
/// The native installer compares the digest of the downloaded file against this
/// value and refuses to install on mismatch, which is the guarantee stated in
/// `spec/guidelines/INVARIANTS.md`. A malformed value cannot satisfy that
/// comparison, so it has to stop the operation before a download starts rather
/// than travel on as part of a CDN URL.
bool isValidApkHash(String? hash) =>
    hash != null && _apkHashPattern.hasMatch(hash);

/// Returns the effective download URL for [target], or `null` when the target
/// cannot be fetched safely.
///
/// A URL taken from metadata is used verbatim only when it is an `https` URL on
/// [kZapstoreCdnHost]. Anything else — a different host, or the same host over
/// cleartext — is routed through the CDN redirect endpoint, which addresses the
/// artefact by content hash.
///
/// Cleartext is currently blocked by the platform (`targetSdk` 36 with no
/// cleartext opt-in), so accepting `http` here could only ever produce a failed
/// download. Checking the scheme keeps that true if the network configuration
/// ever changes, and keeps the tracking headers off a plaintext connection.
String? resolveDownloadUrl(Installable target) {
  if (!isValidApkHash(target.hash)) return null;

  final first = target.urls.firstOrNull;
  if (first == null || first.isEmpty) return null;

  final uri = Uri.tryParse(first);
  if (uri != null && uri.scheme == 'https' && uri.host == kZapstoreCdnHost) {
    return first;
  }
  return 'https://$kZapstoreCdnHost/${target.hash}?redirect=true';
}
