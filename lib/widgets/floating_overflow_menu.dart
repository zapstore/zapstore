import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:zapstore/services/bookmarks_service.dart';
import 'package:zapstore/services/device_key_service.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/utils/nostr_route.dart';
import 'package:zapstore/widgets/app_report_sheet.dart';

/// Floating three-dot overflow menu reusable across detail screens.
///
/// For apps: pass [app] to enable save/open/delete actions.
/// For stacks or other entities: omit [app] — only share, copy link,
/// view publisher, and open-in-browser are shown.
class FloatingOverflowMenu extends HookConsumerWidget {
  const FloatingOverflowMenu({
    super.key,
    required this.shareUrl,
    required this.publisherPubkey,
    this.app,
  });

  final String shareUrl;
  final String publisherPubkey;
  final App? app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hasDeviceKey = ref.watch(devicePubkeyProvider) != null;

    final isInstalled =
        app != null &&
        ref.watch(installedPackageProvider(app!.identifier)) != null;

    // Bookmark state (only relevant when app is provided)
    final isSaved = app != null ? _watchIsSaved(ref) : false;

    return Positioned(
      top: 8,
      right: 8,
      child: Material(
        color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.9),
        shape: const CircleBorder(),
        elevation: 2,
        child: PopupMenuButton<String>(
          icon: const Icon(Icons.more_vert),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          onSelected: (value) =>
              _onSelected(context, ref, value, isSaved: isSaved),
          itemBuilder: (_) => [
            _menuItem('share', Icons.share, 'Share'),
            _menuItem('copy_link', Icons.link, 'Copy link'),
            if (app != null && hasDeviceKey)
              PopupMenuItem<String>(
                value: 'save_app',
                child: Row(
                  children: [
                    Icon(isSaved ? Icons.bookmark : Icons.bookmark_border),
                    const SizedBox(width: 12),
                    Text(isSaved ? 'Remove from saved' : 'Save app'),
                  ],
                ),
              ),
            _menuItem('view_publisher', Icons.person, 'View publisher'),
            _menuItem('open_browser', Icons.open_in_browser, 'Open in browser'),
            if (app != null && canReportApp(app!))
              _menuItem('report_app', Icons.flag_outlined, 'Report app'),
            if (app != null && isInstalled) ...[
              _menuItem('open', Icons.open_in_new, 'Open'),
              _menuItem('delete', Icons.delete_outline, 'Delete'),
            ],
          ],
        ),
      ),
    );
  }

  bool _watchIsSaved(WidgetRef ref) {
    final savedAppIds = ref.watch(bookmarksProvider);
    final appAddressableId =
        '${app!.event.kind}:${app!.pubkey}:${app!.identifier}';
    return savedAppIds.contains(appAddressableId);
  }

  static PopupMenuItem<String> _menuItem(
    String value,
    IconData icon,
    String label,
  ) {
    return PopupMenuItem<String>(
      value: value,
      child: Row(
        children: [Icon(icon), const SizedBox(width: 12), Text(label)],
      ),
    );
  }

  void _onSelected(
    BuildContext context,
    WidgetRef ref,
    String value, {
    required bool isSaved,
  }) {
    switch (value) {
      case 'share':
        _share(context);
      case 'copy_link':
        _copyLink(context);
      case 'save_app':
        _toggleSaveApp(context, ref, isSaved);
      case 'view_publisher':
        _viewPublisher(context);
      case 'open_browser':
        _openInBrowser(context);
      case 'open':
        _openApp(context, ref);
      case 'delete':
        _uninstallApp(context, ref);
      case 'report_app':
        showAppReportSheet(context, app!);
    }
  }

  // -- Actions ---------------------------------------------------------------

  void _share(BuildContext context) {
    try {
      SharePlus.instance.share(ShareParams(text: shareUrl));
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to share', technicalDetails: '$e');
      }
    }
  }

  void _copyLink(BuildContext context) {
    try {
      Clipboard.setData(ClipboardData(text: shareUrl));
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to copy link', technicalDetails: '$e');
      }
    }
  }

  void _viewPublisher(BuildContext context) {
    pushUser(context, publisherPubkey);
  }

  Future<void> _openInBrowser(BuildContext context) async {
    try {
      final uri = Uri.parse(shareUrl);
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      } else {
        if (context.mounted) {
          context.showError('Could not open browser');
        }
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to open browser', technicalDetails: '$e');
      }
    }
  }

  // -- App-only actions ------------------------------------------------------

  Future<void> _toggleSaveApp(
    BuildContext context,
    WidgetRef ref,
    bool isCurrentlySaved,
  ) async {
    final a = app;
    if (a == null) return;

    try {
      final devicePubkey = ref.read(devicePubkeyProvider);
      if (devicePubkey == null) return;

      final signer = ref.read(Signer.signerProvider(devicePubkey));
      if (signer == null) return;

      final existingStacks = await ref.storage.query(
        RequestFilter<AppStack>(
          authors: {devicePubkey},
          tags: {
            '#d': {kAppBookmarksIdentifier},
          },
        ).toRequest(),
        source: const LocalSource(),
      );
      final existingStack = existingStacks.firstOrNull;

      final existingAppIds = List<String>.from(
        existingStack?.privateAppIds ?? [],
      );

      final appAddressableId = '${a.event.kind}:${a.pubkey}:${a.identifier}';

      if (isCurrentlySaved) {
        existingAppIds.remove(appAddressableId);
      } else {
        if (!existingAppIds.contains(appAddressableId)) {
          existingAppIds.add(appAddressableId);
        }
      }

      final platform = ref.read(packageManagerProvider.notifier).platform;
      final partialStack = PartialAppStack.withEncryptedApps(
        name: 'Saved Apps',
        identifier: kAppBookmarksIdentifier,
        apps: existingAppIds,
        platform: platform,
      );

      final signedStack = await partialStack.signWith(signer);
      await ref.storage.save({signedStack});
      ref.storage.publish({signedStack}, relays: 'AppCatalog');

      if (context.mounted) {
        context.showInfo(
          isCurrentlySaved ? 'App removed from saved' : 'App saved',
        );
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to update bookmark', technicalDetails: '$e');
      }
    }
  }

  Future<void> _openApp(BuildContext context, WidgetRef ref) async {
    final a = app;
    if (a == null) return;

    try {
      final packageManager = ref.read(packageManagerProvider.notifier);
      await packageManager.launchApp(a.identifier);
    } catch (e) {
      if (!context.mounted) return;
      context.showError(
        'Failed to launch ${a.name ?? a.identifier}',
        description:
            'The app may have been uninstalled or moved. Try reinstalling.',
        technicalDetails: '$e',
      );
    }
  }

  Future<void> _uninstallApp(BuildContext context, WidgetRef ref) async {
    final a = app;
    if (a == null) return;

    try {
      final packageManager = ref.read(packageManagerProvider.notifier);
      await packageManager.uninstall(a.identifier);
    } catch (e) {
      if (context.mounted) {
        final message = e.toString();
        if (!message.contains('cancelled')) {
          context.showError('Uninstall failed', technicalDetails: '$e');
        }
      }
    }
  }
}

/// Build a shareable URL for an [App].
String getAppShareUrl(App app) {
  final naddr = Utils.encodeShareableIdentifier(
    AddressInput(
      identifier: app.identifier,
      author: app.pubkey,
      kind: app.event.kind,
      relays: const [kDefaultRelay],
    ),
  );
  return 'https://zapstore.dev/apps/$naddr';
}

/// Build a shareable URL for an [AppStack].
String getStackShareUrl(AppStack stack) {
  final naddr = Utils.encodeShareableIdentifier(
    AddressInput(
      identifier: stack.identifier,
      author: stack.pubkey,
      kind: stack.event.kind,
      relays: const [kDefaultRelay],
    ),
  );
  return 'https://zapstore.dev/stacks/$naddr';
}
