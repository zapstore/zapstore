import 'dart:convert';

import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/auth_widgets.dart';
import 'package:zapstore/widgets/common/base_dialog.dart';

/// Dialog for bookmarking an app privately (encrypted)
class BookmarkDialog extends HookConsumerWidget {
  const BookmarkDialog({
    super.key,
    required this.app,
    required this.isPrivatelySaved,
  });

  final App app;
  final bool isPrivatelySaved;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isSignedIn = ref.watch(Signer.activePubkeyProvider) != null;

    return BaseDialog(
      titleIcon: const Icon(Icons.bookmark),
      title: Text(
        'Private bookmark',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      content: BaseDialogContent(
        children: [
          const SizedBox(height: 8),
          Text(
            'Encrypted and only visible to you. Save apps to find them easily later.',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(
                context,
              ).colorScheme.onSurface.withValues(alpha: 0.7),
            ),
          ),
          if (!isSignedIn) ...[
            const SizedBox(height: 16),
            const SignInPrompt(message: 'Sign in to bookmark apps privately.'),
          ],
        ],
      ),
      actions: [
        BaseDialogAction(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        AsyncButtonBuilder(
          onPressed: () =>
              _togglePrivateSave(context, ref, app, isPrivatelySaved),
          builder: (context, child, callback, buttonState) {
            return FilledButton.icon(
              onPressed: !isSignedIn
                  ? null
                  : buttonState.maybeWhen(
                      loading: () => null,
                      orElse: () => callback,
                    ),
              icon: buttonState.maybeWhen(
                loading: () => const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                orElse: () => Icon(
                  isPrivatelySaved ? Icons.bookmark_remove : Icons.bookmark_add,
                  size: 18,
                ),
              ),
              label: Text(isPrivatelySaved ? 'Remove bookmark' : 'Bookmark'),
            );
          },
          child: const SizedBox.shrink(),
        ),
      ],
    );
  }

  Future<void> _togglePrivateSave(
    BuildContext context,
    WidgetRef ref,
    App app,
    bool isCurrentlySaved,
  ) async {
    try {
      final signer = ref.read(Signer.activeSignerProvider);
      final signedInPubkey = ref.read(Signer.activePubkeyProvider);

      if (signer == null || signedInPubkey == null) {
        if (context.mounted) {
          context.showError(
            'Sign in required',
            description: 'You need to sign in to save apps to your bookmarks.',
          );
        }
        return;
      }

      // Query for existing pack
      final existingPackState = await ref.storage.query(
        RequestFilter<AppPack>(
          authors: {signedInPubkey},
          tags: {
            '#d': {kAppBookmarksIdentifier},
          },
        ).toRequest(),
        source: const LocalSource(),
      );
      final existingPack = existingPackState.firstOrNull;

      // Get existing app IDs by decrypting if pack exists
      List<String> existingAppIds = [];
      if (existingPack != null) {
        try {
          final decryptedContent = await signer.nip44Decrypt(
            existingPack.content,
            signedInPubkey,
          );
          existingAppIds = (jsonDecode(decryptedContent) as List)
              .cast<String>();
        } catch (e) {
          if (context.mounted) {
            context.showError(
              'Could not read existing bookmarks',
              description:
                  'Your previous bookmarks could not be decrypted. Starting fresh.\n\n$e',
            );
          }
        }
      }

      // Modify the list
      final appAddressableId =
          '${app.event.kind}:${app.pubkey}:${app.identifier}';

      if (isCurrentlySaved) {
        existingAppIds.remove(appAddressableId);
      } else {
        if (!existingAppIds.contains(appAddressableId)) {
          existingAppIds.add(appAddressableId);
        }
      }

      // Create new partial pack with updated list
      final partialPack = PartialAppPack.withEncryptedApps(
        name: 'Bookmarks',
        identifier: kAppBookmarksIdentifier,
        apps: existingAppIds,
      );

      // Sign (encrypts the content)
      final signedPack = await partialPack.signWith(signer);

      // Save to local storage and publish to relays
      await ref.storage.save({signedPack});
      ref.storage.publish({signedPack}, source: RemoteSource(relays: 'social'));

      if (context.mounted) {
        Navigator.pop(context);
        context.showInfo(isCurrentlySaved ? 'Bookmark removed' : 'Bookmarked');
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to update bookmark', description: '$e');
      }
    }
  }
}

/// Dialog for managing app packs (public collections)
class AddToPackDialog extends HookConsumerWidget {
  const AddToPackDialog({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    if (signedInPubkey == null) {
      return _AddToPackDialogSignedOut(app: app);
    }
    return _AddToPackDialogSignedIn(app: app, signedInPubkey: signedInPubkey);
  }
}

class _AddToPackDialogSignedOut extends StatelessWidget {
  const _AddToPackDialogSignedOut({required this.app});

  final App app;

  @override
  Widget build(BuildContext context) {
    return BaseDialog(
      titleIcon: const Icon(Icons.apps),
      title: Text(
        'Manage App Packs',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      content: BaseDialogContent(
        children: [
          const SizedBox(height: 8),
          Row(
            children: [
              Icon(
                Icons.public,
                size: 16,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Add or remove ${app.name} from public app packs',
                  style: Theme.of(
                    context,
                  ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          const SignInPrompt(
            message: 'Sign in to create and manage your public app packs.',
          ),
        ],
      ),
      actions: [
        BaseDialogAction(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton.icon(
          onPressed: null,
          icon: const Icon(Icons.save, size: 18),
          label: const Text('Save'),
        ),
      ],
    );
  }
}

class _AddToPackDialogSignedIn extends HookConsumerWidget {
  const _AddToPackDialogSignedIn({
    required this.app,
    required this.signedInPubkey,
  });

  final App app;
  final String signedInPubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final newPackNameController = useTextEditingController();

    final publicPacksState = ref.watch(
      query<AppPack>(
        authors: {signedInPubkey},
        and: (pack) => {pack.apps},
        source: const LocalAndRemoteSource(relays: 'social', stream: false),
        andSource: const LocalSource(),
        subscriptionPrefix: 'user-packs-dialog',
      ),
    );

    final publicPacks = publicPacksState.models
        .where((pack) => pack.identifier != kAppBookmarksIdentifier)
        .toList();

    // Check which packs contain this app
    final selectedCollections = useState<Set<String>>({});
    // Track new packs with their names (identifier -> name)
    final newPackNames = useState<Map<String, String>>({});

    useEffect(() {
      final selected = <String>{};
      for (final pack in publicPacks) {
        if (pack.apps.toList().any((appModel) => appModel.id == app.id)) {
          selected.add(pack.identifier);
        }
      }
      selectedCollections.value = selected;
      return null;
    }, [app.id, publicPacks.length]);

    // Helper to slugify name to identifier
    String slugify(String text) {
      return text
          .toLowerCase()
          .trim()
          .replaceAll(RegExp(r'[^\w\s-]'), '')
          .replaceAll(RegExp(r'\s+'), '-')
          .replaceAll(RegExp(r'-+'), '-');
    }

    return BaseDialog(
      titleIcon: const Icon(Icons.apps),
      title: Text(
        'Manage App Packs',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      content: BaseDialogContent(
        children: [
          const SizedBox(height: 8),
          Row(
            children: [
              Icon(
                Icons.public,
                size: 16,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Add or remove ${app.name} from public app packs',
                  style: Theme.of(
                    context,
                  ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),

          // Show existing packs and newly created packs as selectable chips
          if (publicPacks.isNotEmpty || newPackNames.value.isNotEmpty) ...[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                // Existing packs
                ...publicPacks.map((pack) {
                  final isSelected = selectedCollections.value.contains(
                    pack.identifier,
                  );
                  final displayName = pack.name ?? pack.identifier;
                  return FilterChip(
                    label: Text(displayName),
                    selected: isSelected,
                    onSelected: (selected) {
                      if (selected) {
                        selectedCollections.value = {
                          ...selectedCollections.value,
                          pack.identifier,
                        };
                      } else {
                        selectedCollections.value = {
                          ...selectedCollections.value,
                        }..remove(pack.identifier);
                      }
                    },
                    backgroundColor: isSelected
                        ? Theme.of(context).colorScheme.primary
                        : Theme.of(context).colorScheme.surfaceContainerHighest,
                    selectedColor: Theme.of(context).colorScheme.primary,
                    labelStyle: TextStyle(
                      color: isSelected
                          ? Theme.of(context).colorScheme.onPrimary
                          : Theme.of(context).colorScheme.onSurface,
                      fontSize: 13,
                    ),
                    labelPadding: const EdgeInsets.symmetric(
                      horizontal: 8,
                      vertical: 0,
                    ),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 6,
                    ),
                    side: BorderSide.none,
                  );
                }),
                // Newly created packs not yet in publicPacks
                ...newPackNames.value.entries
                    .where(
                      (entry) => !publicPacks.any(
                        (pack) => pack.identifier == entry.key,
                      ),
                    )
                    .map((entry) {
                      final identifier = entry.key;
                      final name = entry.value;
                      final isSelected = selectedCollections.value.contains(
                        identifier,
                      );
                      return FilterChip(
                        label: Text(name),
                        selected: isSelected,
                        onSelected: (selected) {
                          if (selected) {
                            selectedCollections.value = {
                              ...selectedCollections.value,
                              identifier,
                            };
                          } else {
                            selectedCollections.value = {
                              ...selectedCollections.value,
                            }..remove(identifier);
                            // Also remove from newPackNames if deselected
                            final updatedNames = Map<String, String>.from(
                              newPackNames.value,
                            )..remove(identifier);
                            newPackNames.value = updatedNames;
                          }
                        },
                        backgroundColor: isSelected
                            ? Theme.of(context).colorScheme.primary
                            : Theme.of(
                                context,
                              ).colorScheme.surfaceContainerHighest,
                        selectedColor: Theme.of(context).colorScheme.primary,
                        labelStyle: TextStyle(
                          color: isSelected
                              ? Theme.of(context).colorScheme.onPrimary
                              : Theme.of(context).colorScheme.onSurface,
                          fontSize: 13,
                        ),
                        labelPadding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 0,
                        ),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 4,
                          vertical: 6,
                        ),
                        side: BorderSide.none,
                      );
                    }),
              ],
            ),
            const SizedBox(height: 16),
          ],

          // Input for new pack
          TextField(
            controller: newPackNameController,
            maxLength: 20,
            decoration: const InputDecoration(
              labelText: 'Add to new app pack',
              hintText: 'e.g., Favorite Apps',
              border: OutlineInputBorder(),
            ),
            onSubmitted: (text) {
              final name = text.trim();
              final identifier = slugify(name);
              if (name.isNotEmpty &&
                  identifier.isNotEmpty &&
                  !selectedCollections.value.contains(identifier)) {
                selectedCollections.value = {
                  ...selectedCollections.value,
                  identifier,
                };
                newPackNames.value = {...newPackNames.value, identifier: name};
                newPackNameController.clear();
              }
            },
          ),
        ],
      ),
      actions: [
        BaseDialogAction(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        AsyncButtonBuilder(
          onPressed: () async {
            // Check if there's a pending new pack name in the text field
            final pendingPackName = newPackNameController.text.trim();
            if (pendingPackName.isNotEmpty) {
              final identifier = slugify(pendingPackName);
              if (identifier.isNotEmpty &&
                  !selectedCollections.value.contains(identifier)) {
                // Add the pending pack to the collections
                selectedCollections.value = {
                  ...selectedCollections.value,
                  identifier,
                };
                newPackNames.value = {
                  ...newPackNames.value,
                  identifier: pendingPackName,
                };
              }
            }

            // Now save with the updated collections
            await _savePublicCollections(
              context,
              ref,
              app,
              publicPacks,
              selectedCollections.value,
              newPackNames.value,
            );
          },
          builder: (context, child, callback, buttonState) {
            return FilledButton.icon(
              onPressed: buttonState.maybeWhen(
                loading: () => null,
                orElse: () => callback,
              ),
              icon: buttonState.maybeWhen(
                loading: () => const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                orElse: () => const Icon(Icons.save, size: 18),
              ),
              label: const Text('Save'),
            );
          },
          child: const SizedBox.shrink(),
        ),
      ],
    );
  }

  Future<void> _savePublicCollections(
    BuildContext context,
    WidgetRef ref,
    App app,
    List<AppPack> existingPacks,
    Set<String> selectedCollectionIds,
    Map<String, String> newPackNames,
  ) async {
    try {
      final signer = ref.read(Signer.activeSignerProvider);
      if (signer == null) {
        if (context.mounted) {
          context.showError(
            'Sign in required',
            description: 'You need to sign in to manage app packs.',
          );
        }
        return;
      }

      // Get current state of which collections contain this app
      final currentCollectionsWithApp = <String>{};
      for (final pack in existingPacks) {
        // Get raw app IDs from event tags
        final appIds = pack.event.getTagSetValues('a');
        if (appIds.contains(app.id)) {
          currentCollectionsWithApp.add(pack.identifier);
        }
      }

      // Collections to remove from
      final collectionsToRemoveFrom = currentCollectionsWithApp.difference(
        selectedCollectionIds,
      );

      // Update or create collections
      for (final collectionId in selectedCollectionIds) {
        final existingPack = existingPacks
            .where((pack) => pack.identifier == collectionId)
            .firstOrNull;

        // Use the provided name from newPackNames, or existing pack name, or identifier
        final packName =
            newPackNames[collectionId] ?? existingPack?.name ?? collectionId;

        // Get existing app IDs as a list to preserve order
        final existingAppIds =
            existingPack?.event.getTagSetValues('a').toList() ?? [];

        // Check if app already exists in the pack
        final appAlreadyExists = existingAppIds.contains(app.id);

        // Only update if the app is not already in the pack
        if (!appAlreadyExists) {
          final partialPack = PartialAppPack(
            name: packName,
            identifier: collectionId,
          );

          // Add existing apps in order
          for (final appId in existingAppIds) {
            partialPack.addApp(appId);
          }

          // Add this app at the end
          partialPack.addApp(app.id);

          final signedPack = await partialPack.signWith(signer);
          await ref.storage.save({signedPack});
          await ref.storage.publish({
            signedPack,
          }, source: RemoteSource(relays: 'social'));
        }
      }

      // Remove from deselected collections
      for (final collectionId in collectionsToRemoveFrom) {
        final existingPack = existingPacks
            .where((pack) => pack.identifier == collectionId)
            .firstOrNull;
        if (existingPack != null) {
          final partialPack = PartialAppPack(
            name: existingPack.name ?? collectionId,
            identifier: collectionId,
          );

          // Re-add all apps except the one we're removing, preserving order
          // Get raw app IDs from event tags as a list to preserve order
          final existingAppIds = existingPack.event
              .getTagSetValues('a')
              .toList();
          for (final appId in existingAppIds) {
            if (appId != app.id) {
              partialPack.addApp(appId);
            }
          }

          final signedPack = await partialPack.signWith(signer);
          await ref.storage.save({signedPack});
          await ref.storage.publish({
            signedPack,
          }, source: RemoteSource(relays: 'social'));
        }
      }

      if (context.mounted) {
        if (selectedCollectionIds.isEmpty) {
          context.showInfo('Removed from all app packs');
        } else {
          context.showInfo('App packs updated');
        }
        Navigator.pop(context);
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to save', description: '$e');
      }
    }
  }
}
