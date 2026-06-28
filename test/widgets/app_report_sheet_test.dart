import 'package:flutter_test/flutter_test.dart';
import 'package:models/models.dart';
import 'package:zapstore/widgets/app_report_sheet.dart';

void main() {
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
}
