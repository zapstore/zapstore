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

/// Dialog for saving an app privately (encrypted)
class SaveAppDialog extends HookConsumerWidget {
  const SaveAppDialog({
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
        'Save App Privately',
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
            const SignInPrompt(message: 'Sign in to save apps privately.'),
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
              label: Text(isPrivatelySaved ? 'Remove' : 'Save'),
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
            description: 'You need to sign in to save apps privately.',
          );
        }
        return;
      }

      // Query for existing stack
      final existingStackState = await ref.storage.query(
        RequestFilter<AppStack>(
          authors: {signedInPubkey},
          tags: {
            '#d': {kAppBookmarksIdentifier},
          },
        ).toRequest(),
        source: const LocalSource(),
      );
      final existingStack = existingStackState.firstOrNull;

      // Get existing app IDs by decrypting if stack exists
      List<String> existingAppIds = [];
      if (existingStack != null) {
        try {
          final decryptedContent = await signer.nip44Decrypt(
            existingStack.content,
            signedInPubkey,
          );
          existingAppIds = (jsonDecode(decryptedContent) as List)
              .cast<String>();
        } catch (e) {
          if (context.mounted) {
            context.showError(
              'Could not read existing saved apps',
              description:
                  'Your previous saved apps could not be decrypted. Starting fresh.\n\n$e',
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

      // Create new partial stack with updated list
      final partialStack = PartialAppStack.withEncryptedApps(
        name: 'Saved Apps',
        identifier: kAppBookmarksIdentifier,
        apps: existingAppIds,
      );

      // Sign (encrypts the content)
      final signedStack = await partialStack.signWith(signer);

      // Save to local storage and publish to relays
      await ref.storage.save({signedStack});
      ref.storage.publish({
        signedStack,
      }, source: RemoteSource(relays: 'social'));

      if (context.mounted) {
        Navigator.pop(context);
        context.showInfo(
          isCurrentlySaved ? 'App removed from saved' : 'App saved privately',
        );
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to update bookmark', description: '$e');
      }
    }
  }
}

/// Dialog for managing app stacks (public collections)
class AddToStackDialog extends HookConsumerWidget {
  const AddToStackDialog({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    if (signedInPubkey == null) {
      return _AddToStackDialogSignedOut(app: app);
    }
    return _AddToStackDialogSignedIn(app: app, signedInPubkey: signedInPubkey);
  }
}

class _AddToStackDialogSignedOut extends StatelessWidget {
  const _AddToStackDialogSignedOut({required this.app});

  final App app;

  @override
  Widget build(BuildContext context) {
    return BaseDialog(
      titleIcon: const Icon(Icons.apps),
      title: Text(
        'Add to App Stacks',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      content: BaseDialogContent(
        children: [
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Icon(
                    Icons.info_outline,
                    size: 18,
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.6),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Add or remove ${app.name} from public app stacks that you share with others.',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(
                        context,
                      ).colorScheme.onSurface.withValues(alpha: 0.6),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SignInPrompt(
            message: 'Sign in to create and manage your public app stacks.',
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

class _AddToStackDialogSignedIn extends HookConsumerWidget {
  const _AddToStackDialogSignedIn({
    required this.app,
    required this.signedInPubkey,
  });

  final App app;
  final String signedInPubkey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final newStackNameController = useTextEditingController();

    final publicStacksState = ref.watch(
      query<AppStack>(
        authors: {signedInPubkey},
        and: (stack) => {
          stack.apps.query(source: const LocalSource()),
        },
        source: const LocalAndRemoteSource(relays: 'social', stream: false),
        subscriptionPrefix: 'user-stacks-dialog',
        // Filter at query level: exclude saved-apps stack and stacks with no app references
        schemaFilter: (event) {
          final tags = event['tags'] as List?;
          if (tags == null) return false;
          // Check d tag is not the bookmarks identifier
          final dTag = tags.firstWhere(
            (t) => t is List && t.isNotEmpty && t[0] == 'd',
            orElse: () => null,
          );
          if (dTag != null && dTag[1] == kAppBookmarksIdentifier) return false;
          // Check has at least one 'a' tag (app reference)
          final hasAppRef = tags.any(
            (t) => t is List && t.isNotEmpty && t[0] == 'a',
          );
          return hasAppRef;
        },
      ),
    );

    final publicStacks = publicStacksState.models.toList();

    // Check which stacks contain this app
    final selectedCollections = useState<Set<String>>({});
    // Track new stacks with their names (identifier -> name)
    final newStackNames = useState<Map<String, String>>({});

    useEffect(() {
      final selected = <String>{};
      for (final stack in publicStacks) {
        if (stack.apps.toList().any((appModel) => appModel.id == app.id)) {
          selected.add(stack.identifier);
        }
      }
      selectedCollections.value = selected;
      return null;
    }, [app.id, publicStacks.length]);

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
        'Add to App Stacks',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      content: BaseDialogContent(
        children: [
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Icon(
                    Icons.info_outline,
                    size: 18,
                    color: Theme.of(
                      context,
                    ).colorScheme.onSurface.withValues(alpha: 0.6),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Add or remove ${app.name} from public app stacks that you share with others.',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(
                        context,
                      ).colorScheme.onSurface.withValues(alpha: 0.6),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // Show existing stacks and newly created stacks as selectable chips
          if (publicStacks.isNotEmpty || newStackNames.value.isNotEmpty) ...[
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                // Existing stacks
                ...publicStacks.map((stack) {
                  final isSelected = selectedCollections.value.contains(
                    stack.identifier,
                  );
                  final displayName = stack.name ?? stack.identifier;
                  return FilterChip(
                    label: Text(displayName),
                    selected: isSelected,
                    onSelected: (selected) {
                      if (selected) {
                        selectedCollections.value = {
                          ...selectedCollections.value,
                          stack.identifier,
                        };
                      } else {
                        selectedCollections.value = {
                          ...selectedCollections.value,
                        }..remove(stack.identifier);
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
                // Newly created stacks not yet in publicStacks
                ...newStackNames.value.entries
                    .where(
                      (entry) => !publicStacks.any(
                        (stack) => stack.identifier == entry.key,
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
                            // Also remove from newStackNames if deselected
                            final updatedNames = Map<String, String>.from(
                              newStackNames.value,
                            )..remove(identifier);
                            newStackNames.value = updatedNames;
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

          // Input for new stack
          TextField(
            controller: newStackNameController,
            maxLength: 20,
            decoration: const InputDecoration(
              labelText: 'Add to new app stack',
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
                newStackNames.value = {
                  ...newStackNames.value,
                  identifier: name,
                };
                newStackNameController.clear();
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
            // Check if there's a pending new stack name in the text field
            final pendingStackName = newStackNameController.text.trim();
            if (pendingStackName.isNotEmpty) {
              final identifier = slugify(pendingStackName);
              if (identifier.isNotEmpty &&
                  !selectedCollections.value.contains(identifier)) {
                // Add the pending stack to the collections
                selectedCollections.value = {
                  ...selectedCollections.value,
                  identifier,
                };
                newStackNames.value = {
                  ...newStackNames.value,
                  identifier: pendingStackName,
                };
              }
            }

            // Now save with the updated collections
            await _savePublicCollections(
              context,
              ref,
              app,
              publicStacks,
              selectedCollections.value,
              newStackNames.value,
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
    List<AppStack> existingStacks,
    Set<String> selectedCollectionIds,
    Map<String, String> newStackNames,
  ) async {
    try {
      final signer = ref.read(Signer.activeSignerProvider);
      if (signer == null) {
        if (context.mounted) {
          context.showError(
            'Sign in required',
            description: 'You need to sign in to manage app stacks.',
          );
        }
        return;
      }

      // Get current state of which collections contain this app
      final currentCollectionsWithApp = <String>{};
      for (final stack in existingStacks) {
        // Get raw app IDs from event tags
        final appIds = stack.event.getTagSetValues('a');
        if (appIds.contains(app.id)) {
          currentCollectionsWithApp.add(stack.identifier);
        }
      }

      // Collections to remove from
      final collectionsToRemoveFrom = currentCollectionsWithApp.difference(
        selectedCollectionIds,
      );

      // Update or create collections
      for (final collectionId in selectedCollectionIds) {
        final existingStack = existingStacks
            .where((stack) => stack.identifier == collectionId)
            .firstOrNull;

        // Use the provided name from newStackNames, or existing stack name, or identifier
        final stackName =
            newStackNames[collectionId] ?? existingStack?.name ?? collectionId;

        // Get existing app IDs as a list to preserve order
        final existingAppIds =
            existingStack?.event.getTagSetValues('a').toList() ?? [];

        // Check if app already exists in the stack
        final appAlreadyExists = existingAppIds.contains(app.id);

        // Only update if the app is not already in the stack
        if (!appAlreadyExists) {
          final partialStack = PartialAppStack(
            name: stackName,
            identifier: collectionId,
          );

          // Add existing apps in order
          for (final appId in existingAppIds) {
            partialStack.addApp(appId);
          }

          // Add this app at the end
          partialStack.addApp(app.id);

          final signedStack = await partialStack.signWith(signer);
          await ref.storage.save({signedStack});
          await ref.storage.publish({
            signedStack,
          }, source: RemoteSource(relays: 'social'));
        }
      }

      // Remove from deselected collections
      for (final collectionId in collectionsToRemoveFrom) {
        final existingStack = existingStacks
            .where((stack) => stack.identifier == collectionId)
            .firstOrNull;
        if (existingStack != null) {
          final partialStack = PartialAppStack(
            name: existingStack.name ?? collectionId,
            identifier: collectionId,
          );

          // Re-add all apps except the one we're removing, preserving order
          // Get raw app IDs from event tags as a list to preserve order
          final existingAppIds = existingStack.event
              .getTagSetValues('a')
              .toList();
          for (final appId in existingAppIds) {
            if (appId != app.id) {
              partialStack.addApp(appId);
            }
          }

          final signedStack = await partialStack.signWith(signer);
          await ref.storage.save({signedStack});
          await ref.storage.publish({
            signedStack,
          }, source: RemoteSource(relays: 'social'));
        }
      }

      if (context.mounted) {
        if (selectedCollectionIds.isEmpty) {
          context.showInfo('Removed from all app stacks');
        } else {
          context.showInfo('App stacks updated');
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
