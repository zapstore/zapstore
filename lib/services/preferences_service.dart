import 'package:hooks_riverpod/hooks_riverpod.dart';

/// Service to manage user preferences
/// Note: Theme preferences removed as app is dark mode only
class PreferencesService {
  const PreferencesService(this.ref);

  final Ref ref;

  // Future preference methods can be added here
}

final preferencesServiceProvider = Provider<PreferencesService>(
  (ref) => PreferencesService(ref),
);
