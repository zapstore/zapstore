import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:models/models.dart';
import 'package:purplebase/purplebase.dart';
import 'package:zapstore/constants/app_constants.dart';
import 'package:zapstore/router.dart';
import 'package:zapstore/services/app_restart_service.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/device_private_event_service.dart';
import 'package:zapstore/services/log_service.dart';

const _handoffKey = 'pending_app_catalog_relay_event';
const _handoffStorage = FlutterSecureStorage(
  aOptions: AndroidOptions(encryptedSharedPreferences: true),
  iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
);

class AppCatalogRelayState {
  const AppCatalogRelayState({
    this.relays = const {kDefaultRelay},
    this.isChecking = false,
    this.error,
  });

  final Set<String> relays;
  final bool isChecking;
  final Object? error;

  AppCatalogRelayState copyWith({
    Set<String>? relays,
    bool? isChecking,
    Object? error,
    bool clearError = false,
  }) {
    return AppCatalogRelayState(
      relays: relays ?? this.relays,
      isChecking: isChecking ?? this.isChecking,
      error: clearError ? null : (error ?? this.error),
    );
  }
}

enum RelayUpdateAction { none, offerRemote, publishLocal }

final appCatalogRelayStateProvider = StateProvider<AppCatalogRelayState>(
  (_) => const AppCatalogRelayState(),
);

final appCatalogRelayServiceProvider = Provider<AppCatalogRelayService>((ref) {
  final service = AppCatalogRelayService(ref);
  ref.onDispose(service.cancelCurrentCheck);
  return service;
});

class AppCatalogRelayService {
  AppCatalogRelayService(this.ref);

  final Ref ref;
  Request<AppCatalogRelayList>? _activeRequest;
  bool _isChecking = false;
  bool _cancelRequested = false;

  Future<bool> hasRestartHandoff() async {
    try {
      final raw = await _handoffStorage.read(key: _handoffKey);
      return raw != null && raw.isNotEmpty;
    } catch (error, stack) {
      LogService.I.warn(
        'relay restart handoff check failed',
        tag: 'relays',
        err: error,
        stack: stack,
      );
      return false;
    }
  }

  /// Restores a one-restart event handoff and loads the accepted local event.
  ///
  /// Must run after Purplebase and the device signer are initialized.
  Future<void> initializeAcceptedRelays() async {
    final restored = await _restoreHandoff();
    final local = restored ?? await _loadAcceptedLocal();
    final relays = _validRelays(local) ?? const {kDefaultRelay};
    _applyRelayGroup(relays);

    if (restored != null) {
      unawaited(
        ref
            .read(devicePrivateEventServiceProvider)
            .queueExistingDraft(restored),
      );
    }
  }

  Future<void> createAndRestart(Set<String> relayUrls) async {
    final relays = normalizeRelaySet(relayUrls);
    if (relays.isEmpty) {
      throw StateError('At least one valid relay is required');
    }

    final devicePubkey = ref.read(devicePubkeyProvider);
    if (devicePubkey == null) {
      throw StateError('Device key is not ready');
    }
    final draft = await ref
        .read(devicePrivateEventServiceProvider)
        .saveDraftAndQueue(PartialAppCatalogRelayList(relays: relays));
    if (draft is! AppCatalogRelayList) {
      throw StateError('Could not save the local AppCatalog relay-list draft.');
    }
    await _stageAndRestart(draft);
  }

  /// Checks only the hardcoded Zapstore relay for this device's list.
  Future<void> checkForUpdates() async {
    if (_isChecking) return;
    final storage = ref.read(storageNotifierProvider.notifier);
    if (!storage.isInitialized) return;
    final devicePubkey = ref.read(devicePubkeyProvider);
    if (devicePubkey == null) return;

    _isChecking = true;
    _cancelRequested = false;
    _setState(isChecking: true, clearError: true);

    try {
      final local = await _loadAcceptedLocal();
      if (_cancelRequested) return;
      final request = RequestFilter<AppCatalogRelayList>(
        authors: {devicePubkey},
        limit: 1,
      ).toRequest();
      _activeRequest = request;

      final remote = await storage.query(
        request,
        source: const RemoteSource(relays: {kDefaultRelay}, stream: false),
        subscriptionPrefix: 'app-relay-list-check',
      );
      final candidate = _latestValid(remote, devicePubkey);
      final currentRelays = ref.read(appCatalogRelayStateProvider).relays;
      final candidateRelays = _validRelays(candidate);
      final action = decideRelayUpdate(
        currentRelays: currentRelays,
        localCreatedAt: local?.createdAt,
        remoteRelays: candidateRelays,
        remoteCreatedAt: candidate?.createdAt,
      );
      switch (action) {
        case RelayUpdateAction.none:
          return;
        case RelayUpdateAction.publishLocal:
          if (local != null) await _publish(local);
          return;
        case RelayUpdateAction.offerRemote:
          break;
      }

      final offeredCandidate = candidate!;
      final offeredRelays = candidateRelays!;

      // Remote queries persist results. Put the accepted event back before
      // asking so an unconfirmed candidate can never become active.
      await _restoreAcceptedAfterPreview(offeredCandidate, local);

      final confirmed = await _confirmRemoteChange(
        currentRelays,
        offeredRelays,
      );
      if (confirmed) {
        await _stageAndRestart(offeredCandidate);
      }
    } catch (error, stack) {
      if (!_cancelRequested) {
        LogService.I.warn(
          'app catalog relay check failed',
          tag: 'relays',
          err: error,
          stack: stack,
        );
        _setState(error: error);
      }
    } finally {
      _activeRequest = null;
      _isChecking = false;
      _cancelRequested = false;
      _setState(isChecking: false);
    }
  }

  void cancelCurrentCheck() {
    _cancelRequested = true;
    final request = _activeRequest;
    _activeRequest = null;
    _setState(isChecking: false);
    if (request != null) {
      unawaited(ref.read(storageNotifierProvider.notifier).cancel(request));
    }
  }

  Future<AppCatalogRelayList?> _restoreHandoff() async {
    String? raw;
    try {
      raw = await _handoffStorage.read(key: _handoffKey);
    } catch (error, stack) {
      LogService.I.warn(
        'relay restart handoff read failed',
        tag: 'relays',
        err: error,
        stack: stack,
      );
      return null;
    }
    if (raw == null || raw.isEmpty) return null;

    try {
      final map = Map<String, dynamic>.from(jsonDecode(raw) as Map);
      final devicePubkey = ref.read(devicePubkeyProvider);
      if (devicePubkey == null ||
          map['kind'] != 10067 ||
          map['pubkey'] != devicePubkey ||
          !ref.read(verifierProvider).verify(map)) {
        return null;
      }

      final event = AppCatalogRelayList.fromMap(map, ref);
      if (_validRelays(event) == null) return null;
      final saved = await ref.read(storageNotifierProvider.notifier).save({
        event,
      });
      return saved ? event : null;
    } catch (error, stack) {
      LogService.I.warn(
        'relay restart handoff restore failed',
        tag: 'relays',
        err: error,
        stack: stack,
      );
      return null;
    } finally {
      await _clearHandoff();
    }
  }

  Future<AppCatalogRelayList?> _loadAcceptedLocal() async {
    final devicePubkey = ref.read(devicePubkeyProvider);
    if (devicePubkey == null) return null;

    final events = await ref
        .read(storageNotifierProvider.notifier)
        .query(
          RequestFilter<AppCatalogRelayList>(
            authors: {devicePubkey},
            limit: 1,
          ).toRequest(),
          source: const LocalSource(),
          subscriptionPrefix: 'app-relay-list-local',
        );
    return _latestValid(events, devicePubkey);
  }

  AppCatalogRelayList? _latestValid(
    Iterable<AppCatalogRelayList> events,
    String devicePubkey,
  ) {
    AppCatalogRelayList? latest;
    for (final event in events) {
      if (event.pubkey != devicePubkey ||
          event.content.isNotEmpty ||
          _validRelays(event) == null ||
          !ref.read(verifierProvider).verify(event.toMap())) {
        continue;
      }
      if (latest == null || event.createdAt.isAfter(latest.createdAt)) {
        latest = event;
      }
    }
    return latest;
  }

  Set<String>? _validRelays(AppCatalogRelayList? event) {
    if (event == null || event.content.isNotEmpty) return null;
    final normalized = normalizeRelaySet(event.relays);
    if (normalized.isEmpty || normalized.length != event.relays.length) {
      return null;
    }
    return normalized;
  }

  Future<void> _restoreAcceptedAfterPreview(
    AppCatalogRelayList candidate,
    AppCatalogRelayList? accepted,
  ) async {
    final storage = ref.read(storageNotifierProvider.notifier);
    if (storage is PurplebaseStorageNotifier) {
      await storage.delete({candidate.event.id});
    }
    if (accepted != null) {
      await storage.save({accepted});
    }
  }

  Future<bool> _confirmRemoteChange(
    Set<String> current,
    Set<String> proposed,
  ) async {
    final context = rootNavigatorKey.currentState?.overlay?.context;
    if (context == null || !context.mounted) return false;

    final currentText = (current.toList()..sort()).join('\n');
    final proposedText = (proposed.toList()..sort()).join('\n');
    return await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('Relay list changed'),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'A new device relay list was found. Applying it will '
                    'clear cached app data and restart Zapstore.',
                  ),
                  const SizedBox(height: 16),
                  const Text(
                    'Current',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  SelectableText(currentText),
                  const SizedBox(height: 12),
                  const Text(
                    'Proposed',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  SelectableText(proposedText),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Keep Current'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Apply & Restart'),
              ),
            ],
          ),
        ) ??
        false;
  }

  Future<void> _stageAndRestart(AppCatalogRelayList event) async {
    await _handoffStorage.write(
      key: _handoffKey,
      value: jsonEncode(event.toMap()),
    );
    try {
      await restartApp();
    } catch (_) {
      await _clearHandoff();
      rethrow;
    }
  }

  Future<void> _clearHandoff() async {
    try {
      await _handoffStorage.delete(key: _handoffKey);
    } catch (error, stack) {
      LogService.I.warn(
        'relay restart handoff cleanup failed',
        tag: 'relays',
        err: error,
        stack: stack,
      );
    }
  }

  Future<void> _publish(AppCatalogRelayList event) async {
    try {
      await ref
          .read(devicePrivateEventServiceProvider)
          .queueExistingDraft(event);
    } catch (error, stack) {
      LogService.I.warn(
        'app catalog relay publish failed',
        tag: 'relays',
        err: error,
        stack: stack,
      );
      _setState(error: error);
    }
  }

  void _applyRelayGroup(Set<String> relays) {
    final normalized = Set<String>.unmodifiable(relays);
    ref
            .read(storageNotifierProvider.notifier)
            .config
            .defaultRelays['AppCatalog'] =
        normalized;
    ref.read(appCatalogRelayStateProvider.notifier).state =
        AppCatalogRelayState(relays: normalized);
  }

  void _setState({bool? isChecking, Object? error, bool clearError = false}) {
    final notifier = ref.read(appCatalogRelayStateProvider.notifier);
    notifier.state = notifier.state.copyWith(
      isChecking: isChecking,
      error: error,
      clearError: clearError,
    );
  }
}

Set<String> normalizeRelaySet(Iterable<String> relays) {
  final normalized = <String>{};
  for (final relay in relays) {
    if (!relay.startsWith('ws://') && !relay.startsWith('wss://')) continue;
    final value = normalizeRelayUrl(relay);
    if (value != null) normalized.add(value);
  }
  return normalized;
}

RelayUpdateAction decideRelayUpdate({
  required Set<String> currentRelays,
  required DateTime? localCreatedAt,
  required Set<String>? remoteRelays,
  required DateTime? remoteCreatedAt,
}) {
  if (remoteRelays == null || remoteCreatedAt == null) {
    return localCreatedAt == null
        ? RelayUpdateAction.none
        : RelayUpdateAction.publishLocal;
  }
  if (localCreatedAt != null && remoteCreatedAt.isBefore(localCreatedAt)) {
    return RelayUpdateAction.publishLocal;
  }
  if (_sameRelays(remoteRelays, currentRelays)) {
    return RelayUpdateAction.none;
  }
  return RelayUpdateAction.offerRemote;
}

bool _sameRelays(Set<String> a, Set<String> b) =>
    a.length == b.length && a.containsAll(b);
