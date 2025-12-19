import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/theme.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/auth_widgets.dart';
import 'package:zapstore/widgets/common/profile_avatar.dart';
import 'package:zapstore/widgets/pill_widget.dart';

class CommentsSection extends HookConsumerWidget {
  const CommentsSection({super.key, required this.app, this.fileMetadata});

  final App app;
  final FileMetadata? fileMetadata;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (fileMetadata == null) {
      return const SizedBox.shrink();
    }

    final commentsState = ref.watch(
      query<Comment>(
        tags: {
          '#A': {app.id},
        },
        source: LocalAndRemoteSource(stream: true, relays: 'social'),
        subscriptionPrefix: 'app-comments',
      ),
    );

    // Extract comments and error state
    final List<Comment> comments = switch (commentsState) {
      StorageData(:final models) => models,
      _ => [],
    };
    final errorException = switch (commentsState) {
      StorageError(:final exception) => exception,
      _ => null,
    };

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header - always visible
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Comments',
                style: Theme.of(
                  context,
                ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
              ),
              if (comments.isNotEmpty)
                _CommentCountBadge(count: comments.length),
            ],
          ),
          const SizedBox(height: 12),
          // Add Comment button - always visible
          _AddCommentButton(fileMetadata: fileMetadata!),
          // Error message if any
          if (errorException != null) ...[
            const SizedBox(height: 16),
            _buildCommentsError(context, errorException),
          ],
          // Comments list - only when there are comments
          if (comments.isNotEmpty) ...[
            const SizedBox(height: 16),
            ...(comments.toList()
                  ..sort((a, b) => b.createdAt.compareTo(a.createdAt)))
                .map(
                  (comment) => Padding(
                    padding: const EdgeInsets.only(bottom: 16),
                    child: _CommentCard(comment: comment),
                  ),
                ),
          ],
        ],
      ),
    );
  }

  Widget _buildCommentsError(BuildContext context, Object exception) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(
            Icons.error_outline,
            color: Theme.of(context).colorScheme.onErrorContainer,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              'Failed to load comments',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onErrorContainer,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CommentCountBadge extends StatelessWidget {
  const _CommentCountBadge({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final display = count > 99 ? '99+' : '$count';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.grey.shade300,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        display,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
          color: Colors.grey.shade700,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _CommentCard extends HookConsumerWidget {
  const _CommentCard({required this.comment});

  final Comment comment;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query author profile with caching
    // Query author profile (comment.event.pubkey is always present)
    final authorState = ref.watch(
      query<Profile>(
        authors: {comment.event.pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    final author = switch (authorState) {
      StorageData(:final models) => models.firstOrNull,
      _ => null,
    };
    // Extract version from v tag (per NIP-22 guidance)
    final version = comment.event.getFirstTagValue('v');

    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ProfileAvatar(profile: author, radius: 16),
                const SizedBox(width: 8),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Wrap(
                              crossAxisAlignment: WrapCrossAlignment.center,
                              spacing: 6,
                              children: [
                                Text(
                                  author?.nameOrNpub ?? '',
                                  style: context.textTheme.titleSmall?.copyWith(
                                    fontWeight: FontWeight.w600,
                                    fontSize:
                                        (context
                                                .textTheme
                                                .titleSmall
                                                ?.fontSize ??
                                            14) *
                                        0.82,
                                  ),
                                ),
                                if (version != null) ...[
                                  Text(
                                    'on',
                                    style: context.textTheme.bodySmall
                                        ?.copyWith(color: Colors.grey[600]),
                                  ),
                                  PillWidget(
                                    TextSpan(text: version),
                                    color: AppColors.darkPillBackground,
                                    size: 8,
                                  ),
                                ],
                              ],
                            ),
                          ),
                          Text(
                            DateFormat('MMM d, y').format(comment.createdAt),
                            style: context.textTheme.bodySmall?.copyWith(
                              color: Colors.grey[600],
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        comment.content,
                        style: context.textTheme.bodyMedium,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _AddCommentButton extends ConsumerWidget {
  const _AddCommentButton({required this.fileMetadata});

  final FileMetadata fileMetadata;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SizedBox(
      width: double.infinity,
      child: FilledButton.icon(
        onPressed: () => _showCommentComposer(context),
        icon: const Icon(Icons.add_comment),
        label: const Text('Add Comment'),
        style: FilledButton.styleFrom(
          backgroundColor: Theme.of(
            context,
          ).colorScheme.surfaceContainerHighest,
          foregroundColor: Theme.of(context).colorScheme.onSurface,
          padding: const EdgeInsets.symmetric(vertical: 12),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
      ),
    );
  }

  void _showCommentComposer(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (context) => _CommentComposer(fileMetadata: fileMetadata),
    );
  }
}

class _CommentComposer extends HookConsumerWidget {
  const _CommentComposer({required this.fileMetadata});

  final FileMetadata fileMetadata;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isSignedIn = ref.watch(Signer.activePubkeyProvider) != null;
    final textController = useTextEditingController();
    // Rebuild on text changes so the Post button enables/disables correctly
    useListenable(textController);

    final app = fileMetadata.release.value?.app.value;
    final installedVersion = app?.installedPackage?.version;
    final versionToComment = installedVersion ?? fileMetadata.version;
    final appName = app?.name ?? 'this app';

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Container(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'Comment on $versionToComment',
                  style: context.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
            const SizedBox(height: 16),
            if (!isSignedIn) ...[
              const SignInPrompt(
                message:
                    'Sign in to share your thoughts and help others discover great apps.',
              ),
            ] else ...[
              TextField(
                controller: textController,
                decoration: InputDecoration(
                  hintText:
                      'Share your thoughts about $appName $versionToComment...',
                  border: const OutlineInputBorder(),
                ),
                maxLines: 4,
                autofocus: true,
              ),
            ],
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: AsyncButtonBuilder(
                child: const Text('Post Comment'),
                onPressed: () =>
                    _publishComment(ref, textController.text, context),
                builder: (context, child, callback, buttonState) {
                  return FilledButton(
                    onPressed: !isSignedIn
                        ? null
                        : buttonState.maybeWhen(
                            loading: () => null,
                            orElse: () => textController.text.trim().isEmpty
                                ? null
                                : callback,
                          ),
                    child: buttonState.maybeWhen(
                      loading: () => const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          valueColor: AlwaysStoppedAnimation<Color>(
                            Colors.white,
                          ),
                        ),
                      ),
                      orElse: () => child,
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _publishComment(
    WidgetRef ref,
    String content,
    BuildContext context,
  ) async {
    if (content.trim().isEmpty) return;

    try {
      final signer = ref.read(Signer.activeSignerProvider);
      if (signer == null) {
        if (context.mounted) {
          context.showError(
            'Sign in required',
            description: 'You need to sign in with Amber to post comments.',
          );
        }
        return;
      }

      final app = fileMetadata.release.value?.app.value;
      if (app == null) {
        if (context.mounted) {
          context.showError(
            'Unable to comment',
            description: 'App information is not available. Try again later.',
          );
        }
        return;
      }

      // Get the version to comment on (installed version or latest release)
      final installedVersion = app.installedPackage?.version;
      final versionToComment = installedVersion ?? fileMetadata.version;

      final comment = PartialComment(
        content: content.trim(),
        rootModel: app,
        // No parentModel for root comments - only A/K/P tags, no e/k/p
      );

      // Add v tag for version (per NIP-22 guidance, not d tag)
      comment.event.addTagValue('v', versionToComment);

      final signedComment = await comment.signWith(signer);

      await signedComment.save();
      await signedComment.publish(source: RemoteSource(relays: 'social'));

      if (context.mounted) {
        Navigator.pop(context);
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to post comment', description: '$e');
      }
    }
  }
}
