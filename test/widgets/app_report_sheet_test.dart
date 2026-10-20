import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/widgets/app_report_sheet.dart';

void main() {
  setUpAll(() {
    Model.register(
      kind: 1984,
      constructor: Report.fromMap,
      partialConstructor: PartialReport.fromMap,
    );
  });

  group('buildAppReport', () {
    test('names the listing coordinate and app id', () {
      final pubkey = 'b' * 64;
      final report = buildAppReport(
        appEventId: 'a' * 64,
        appCoordinate: '32267:$pubkey:com.example.app',
        appId: 'com.example.app',
        authorPubkey: pubkey,
        violationType: ReportType.malware,
        description: 'The APK contains a known malicious payload.',
      );

      expect(
        report.event.getFirstTagValue('a'),
        '32267:$pubkey:com.example.app',
      );
      expect(report.event.getFirstTagValue('i'), 'com.example.app');
      expect(report.event.getFirstTagValue('e'), 'a' * 64);
      expect(report.event.getFirstTagValue('p'), pubkey);
    });
  });

  group('canSubmitAppReport', () {
    test('requires a policy violation', () {
      expect(
        canSubmitAppReport(
          violationType: null,
          description: 'The APK contains a known malicious payload.',
          isPublishing: false,
        ),
        isFalse,
      );
    });

    test('requires a substantive description', () {
      expect(
        canSubmitAppReport(
          violationType: ReportType.malware,
          description: 'Malware',
          isPublishing: false,
        ),
        isFalse,
      );
    });

    test('accepts a category and substantive description', () {
      expect(
        canSubmitAppReport(
          violationType: ReportType.malware,
          description: 'The APK contains a known malicious payload.',
          isPublishing: false,
        ),
        isTrue,
      );
    });

    test('disables submission while publishing', () {
      expect(
        canSubmitAppReport(
          violationType: ReportType.malware,
          description: 'The APK contains a known malicious payload.',
          isPublishing: true,
        ),
        isFalse,
      );
    });
  });

  group('wasAppReportAccepted', () {
    test('accepts an explicit relay acceptance for the report event', () {
      final response = PublishResponse()
        ..addEvent(
          'report-id',
          relayUrl: 'wss://relay.zapstore.dev',
          accepted: true,
        );

      expect(wasAppReportAccepted(response, 'report-id'), isTrue);
    });

    test('rejects a relay rejection or timeout', () {
      final response = PublishResponse()
        ..addEvent(
          'report-id',
          relayUrl: 'wss://relay.zapstore.dev',
          accepted: false,
          message: 'Timeout',
        );

      expect(wasAppReportAccepted(response, 'report-id'), isFalse);
    });

    test('rejects a response without the report event', () {
      final response = PublishResponse()
        ..addEvent(
          'another-event-id',
          relayUrl: 'wss://relay.zapstore.dev',
          accepted: true,
        );

      expect(wasAppReportAccepted(response, 'report-id'), isFalse);
    });
  });

  group('appReportPublishFailure', () {
    test('includes the relay rejection reason', () {
      final response = PublishResponse()
        ..addEvent(
          'report-id',
          relayUrl: 'wss://relay.zapstore.dev',
          accepted: false,
          message: 'rate-limited: slow down chief',
        );

      expect(
        appReportPublishFailure(response, 'report-id'),
        'Relay wss://relay.zapstore.dev rejected the report: '
        'rate-limited: slow down chief',
      );
    });

    test('explains when the report event has no relay response', () {
      final response = PublishResponse();

      expect(
        appReportPublishFailure(response, 'report-id'),
        'No relay responded to the report. Check your connection and retry.',
      );
    });

    test('explains when the relay rejects without a reason', () {
      final response = PublishResponse()
        ..addEvent(
          'report-id',
          relayUrl: 'wss://relay.zapstore.dev',
          accepted: false,
        );

      expect(
        appReportPublishFailure(response, 'report-id'),
        'Relay wss://relay.zapstore.dev explicitly rejected the report without '
        'providing a reason.',
      );
    });
  });
}
