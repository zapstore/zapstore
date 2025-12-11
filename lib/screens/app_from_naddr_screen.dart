import 'package:collection/collection.dart';
import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/widgets/app_detail_widgets.dart';

import 'package:zapstore/screens/app_detail_screen.dart';

class AppFromNaddrScreen extends ConsumerWidget {
  final String naddr;

  const AppFromNaddrScreen({super.key, required this.naddr});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Decode naddr
    AddressData? address;
    try {
      final decoded = Utils.decodeShareableIdentifier(naddr);
      if (decoded is AddressData) {
        address = decoded;
      }
    } catch (_) {
      // ignore - handled below
    }

    if (address == null || address.identifier.isEmpty) {
      return _ErrorScaffold(message: 'Invalid link. Not a valid naddr.');
    }

    // Query the App by its identifier (d-tag)
    final appsState = ref.watch(
      query<App>(
        tags: {
          '#d': {address.identifier},
        },
        limit: 1,
        source: LocalAndRemoteSource(stream: false, background: true),
        subscriptionPrefix: 'naddr-app',
      ),
    );

    final app = appsState.models.firstOrNull;

    if (app != null) {
      return AppDetailScreen(app: app);
    }

    // Handle error state
    switch (appsState) {
      case StorageError(:final exception):
        return _ErrorScaffold(message: exception.toString());
      default:
        break;
    }

    // Loading state
    return const Scaffold(
      body: SafeArea(
        child: Padding(padding: EdgeInsets.all(16), child: AppDetailSkeleton()),
      ),
    );
  }
}

class _ErrorScaffold extends StatelessWidget {
  final String message;
  const _ErrorScaffold({required this.message});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Open App')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline, size: 64),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
