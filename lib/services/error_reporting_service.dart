import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';

/// Service for reporting errors via NIP-44 encrypted DMs to Zapstore team.
///
/// Uses [kAnonymousPrivateKey] for signing so error reports can be sent
/// even when the user is not signed in.
class ErrorReportingService {
  ErrorReportingService(this.ref);

  final Ref ref;

  /// Track reported errors to avoid spam (error hash -> timestamp)
  final Map<int, DateTime> _reportedErrors = {};

  /// Rate limit: one report per error type per session
  static const _rateLimitDuration = Duration(minutes: 30);

  /// Maximum reports per session to prevent abuse
  static const _maxReportsPerSession = 10;
  int _sessionReportCount = 0;

  /// Report an error to the Zapstore team via encrypted DM.
  ///
  /// Rate-limited to prevent spam. Only sends one report per unique
  /// error type per session.
  Future<void> reportError(Object exception, StackTrace? stackTrace) async {
    // Don't report in debug mode
    if (kDebugMode) {
      debugPrint('Error (not reported in debug): $exception');
      debugPrint('$stackTrace');
      return;
    }

    // Check session limit
    if (_sessionReportCount >= _maxReportsPerSession) {
      return;
    }

    // Rate limit by error type
    final errorHash = exception.toString().hashCode;
    final lastReport = _reportedErrors[errorHash];
    if (lastReport != null &&
        DateTime.now().difference(lastReport) < _rateLimitDuration) {
      return;
    }

    try {
      // Create anonymous signer for error reporting
      final signer = Bip340PrivateKeySigner(kAnonymousPrivateKey, ref);
      await signer.signIn(setAsActive: false, registerSigner: false);

      // Format error report
      final report = _formatErrorReport(exception, stackTrace);

      // Create encrypted DM to Zapstore pubkey (uses NIP-44 by default)
      final dm = PartialDirectMessage(
        content: report,
        receiver: kZapstorePubkey,
      );

      // Sign and publish
      final signedDm = await dm.signWith(signer);
      await ref.read(storageNotifierProvider.notifier).publish(
        {signedDm},
        source: const RemoteSource(relays: 'social', stream: false),
      );

      // Update rate limiting
      _reportedErrors[errorHash] = DateTime.now();
      _sessionReportCount++;

      debugPrint('Error report sent successfully');
    } catch (e) {
      // Silently fail - we don't want error reporting to cause more errors
      debugPrint('Failed to send error report: $e');
    }
  }

  String _formatErrorReport(Object exception, StackTrace? stackTrace) {
    final buffer = StringBuffer();

    buffer.writeln('=== ZAPSTORE ERROR REPORT ===');
    buffer.writeln('Timestamp: ${DateTime.now().toUtc().toIso8601String()}');
    buffer.writeln('Platform: ${Platform.operatingSystem}');
    buffer.writeln('OS Version: ${Platform.operatingSystemVersion}');
    buffer.writeln('');
    buffer.writeln('Exception:');
    buffer.writeln(exception.toString());
    buffer.writeln('');

    if (stackTrace != null) {
      buffer.writeln('Stack Trace:');
      // Limit stack trace length to avoid huge messages
      final stackLines = stackTrace.toString().split('\n');
      final limitedStack = stackLines.take(30).join('\n');
      buffer.writeln(limitedStack);
      if (stackLines.length > 30) {
        buffer.writeln('... (${stackLines.length - 30} more lines)');
      }
    }

    return buffer.toString();
  }
}

/// Provider for the error reporting service
final errorReportingServiceProvider = Provider<ErrorReportingService>(
  ErrorReportingService.new,
);

