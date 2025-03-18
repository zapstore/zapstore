import 'package:purplebase/purplebase.dart';

class VerifyReputationRequest extends RegularEvent<VerifyReputationRequest> {
  VerifyReputationRequest.fromJson(super.map) : super.fromJson();
}

class PartialVerifyReputationRequest
    extends RegularPartialEvent<VerifyReputationRequest> {
  PartialVerifyReputationRequest({
    required String source,
    required String target,
  }) {
    event.setTag('param', ['source', source]);
    event.setTag('param', ['target', target]);
  }
}
