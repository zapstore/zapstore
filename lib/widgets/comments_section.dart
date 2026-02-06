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
import 'package:zapstore/widgets/common/profile_name_widget.dart';
import 'package:zapstore/widgets/pill_widget.dart';

/// Comments section for App detail screen
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
        and: (comment) => {comment.replies.query()},
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

    return _CommentsSectionLayout(
      comments: comments,
      errorException: errorException,
      addCommentButton: _AddCommentButton(
        fileMetadata: fileMetadata!,
        app: app,
      ),
      app: app,
      fileMetadata: fileMetadata!,
    );
  }
}

/// Comments section for AppStack/Stack detail screen
class StackCommentsSection extends HookConsumerWidget {
  const StackCommentsSection({super.key, required this.stack});

  final AppStack stack;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final commentsState = ref.watch(
      query<Comment>(
        tags: {
          '#A': {stack.id},
        },
        source: LocalAndRemoteSource(stream: true, relays: 'social'),
        subscriptionPrefix: 'stack-comments',
        and: (comment) => {comment.replies.query()},
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

    return _CommentsSectionLayout(
      comments: comments,
      errorException: errorException,
      addCommentButton: _AddStackCommentButton(stack: stack),
      stack: stack,
    );
  }
}

/// Shared layout for both App and Stack comments
class _CommentsSectionLayout extends StatelessWidget {
  const _CommentsSectionLayout({
    required this.comments,
    required this.errorException,
    required this.addCommentButton,
    this.app,
    this.fileMetadata,
    this.stack,
  });

  final List<Comment> comments;
  final Object? errorException;
  final Widget addCommentButton;
  final App? app;
  final FileMetadata? fileMetadata;
  final AppStack? stack;

  @override
  Widget build(BuildContext context) {
    // Filter to only root comments (those without a parent comment)
    // A root comment has parentKind != 1111 (not replying to another comment)
    final rootComments = comments
        .where((c) => c.parentKind != 1111)
        .toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));

    // Count total including replies
    final totalCount = _countAllComments(rootComments);

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header - always visible
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('Comments', style: Theme.of(context).textTheme.titleLarge),
              if (totalCount > 0) _CommentCountBadge(count: totalCount),
            ],
          ),
          const SizedBox(height: 12),
          // Add Comment button - always visible
          addCommentButton,
          // Error message if any
          if (errorException != null) ...[
            const SizedBox(height: 16),
            _buildCommentsError(context, errorException!),
          ],
          // Comments list - only when there are comments
          if (rootComments.isNotEmpty) ...[
            const SizedBox(height: 16),
            ...rootComments.map(
              (comment) => Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: _ThreadedCommentCard(
                  comment: comment,
                  app: app,
                  fileMetadata: fileMetadata,
                  stack: stack,
                  depth: 0,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  int _countAllComments(List<Comment> comments) {
    int count = 0;
    for (final comment in comments) {
      count++;
      final replies = comment.replies.toList();
      if (replies.isNotEmpty) {
        count += _countAllComments(replies);
      }
    }
    return count;
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

/// Threaded comment card that shows replies nested below
class _ThreadedCommentCard extends HookConsumerWidget {
  const _ThreadedCommentCard({
    required this.comment,
    required this.depth,
    this.app,
    this.fileMetadata,
    this.stack,
  });

  final Comment comment;
  final int depth;
  final App? app;
  final FileMetadata? fileMetadata;
  final AppStack? stack;

  // Thread line colors for different depths
  static const List<Color> _threadColors = [
    Color(0xFF6366F1), // Indigo
    Color(0xFF8B5CF6), // Violet
    Color(0xFFA855F7), // Purple
    Color(0xFFD946EF), // Fuchsia
    Color(0xFFEC4899), // Pink
  ];

  Color _getThreadColor(int depth) {
    return _threadColors[depth % _threadColors.length];
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Query author profile with caching
    final authorState = ref.watch(
      query<Profile>(
        authors: {comment.event.pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          stream: false,
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    final author = authorState.models.firstOrNull;
    final isAuthorLoading = authorState is StorageLoading && author == null;

    // Extract version from v tag (per NIP-22 guidance)
    final version = comment.event.getFirstTagValue('v');

    // Get replies sorted by date
    final replies = comment.replies.toList()
      ..sort((a, b) => a.createdAt.compareTo(b.createdAt));

    final isSignedIn = ref.watch(Signer.activePubkeyProvider) != null;
    final isReply = depth > 0;
    final threadColor = _getThreadColor(depth - 1);

    final nameStyle = context.textTheme.titleSmall?.copyWith(
      fontSize: (context.textTheme.titleSmall?.fontSize ?? 14) *
          (isReply ? 0.78 : 0.82),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Comment card with thread line for replies
        Container(
          decoration: isReply
              ? BoxDecoration(
                  border: Border(
                    left: BorderSide(
                      color: threadColor,
                      width: 3,
                    ),
                  ),
                )
              : null,
          child: Container(
            margin: isReply ? const EdgeInsets.only(left: 8) : null,
            decoration: BoxDecoration(
              color: isReply
                  ? threadColor.withValues(alpha: 0.08)
                  : Theme.of(context).cardColor,
              borderRadius: BorderRadius.circular(8),
              border: isReply
                  ? null
                  : Border.all(
                      color: Theme.of(context)
                          .colorScheme
                          .outline
                          .withValues(alpha: 0.2),
                    ),
            ),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      ProfileAvatar(
                        profile: author,
                        radius: isReply ? 14 : 16,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Expanded(
                                  child: Wrap(
                                    crossAxisAlignment:
                                        WrapCrossAlignment.center,
                                    spacing: 6,
                                    children: [
                                      ProfileNameWidget(
                                        pubkey: comment.event.pubkey,
                                        profile: author,
                                        isLoading: isAuthorLoading,
                                        style: nameStyle,
                                        skeletonWidth: 80,
                                      ),
                                      // Only show version on top-level comments
                                      if (version != null && !isReply) ...[
                                        Text(
                                          'on',
                                          style: context.textTheme.bodySmall
                                              ?.copyWith(
                                                  color: Colors.grey[600]),
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
                                  DateFormat('MMM d, y')
                                      .format(comment.createdAt),
                                  style: context.textTheme.bodySmall?.copyWith(
                                    color: Colors.grey[600],
                                    fontSize: isReply ? 10 : null,
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 6),
                            Text(
                              comment.content,
                              style: context.textTheme.bodyMedium?.copyWith(
                                fontSize: isReply ? 13 : null,
                              ),
                            ),
                            // Reply button
                            if (isSignedIn) ...[
                              const SizedBox(height: 6),
                              GestureDetector(
                                onTap: () => _showReplyComposer(context),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      Icons.reply,
                                      size: 14,
                                      color: Theme.of(context)
                                          .colorScheme
                                          .primary
                                          .withValues(alpha: 0.7),
                                    ),
                                    const SizedBox(width: 4),
                                    Text(
                                      'Reply',
                                      style:
                                          context.textTheme.labelSmall?.copyWith(
                                        color: Theme.of(context)
                                            .colorScheme
                                            .primary
                                            .withValues(alpha: 0.7),
                                        fontSize: 11,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
        // Render replies with minimal spacing
        if (replies.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(left: 12, top: 6),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: replies
                  .map(
                    (reply) => Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: _ThreadedCommentCard(
                        comment: reply,
                        app: app,
                        fileMetadata: fileMetadata,
                        stack: stack,
                        depth: depth + 1,
                      ),
                    ),
                  )
                  .toList(),
            ),
          ),
      ],
    );
  }

  void _showReplyComposer(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (context) => _ReplyComposer(
        parentComment: comment,
        app: app,
        fileMetadata: fileMetadata,
        stack: stack,
      ),
    );
  }
}

class _AddCommentButton extends ConsumerWidget {
  const _AddCommentButton({required this.fileMetadata, required this.app});

  final FileMetadata fileMetadata;
  final App app;

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
      builder: (context) => _CommentComposer(fileMetadata: fileMetadata, app: app),
    );
  }
}

class _AddStackCommentButton extends ConsumerWidget {
  const _AddStackCommentButton({required this.stack});

  final AppStack stack;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return SizedBox(
      width: double.infinity,
      child: FilledButton.icon(
        onPressed: () => _showStackCommentComposer(context),
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

  void _showStackCommentComposer(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (context) => _StackCommentComposer(stack: stack),
    );
  }
}

class _CommentComposer extends HookConsumerWidget {
  const _CommentComposer({required this.fileMetadata, required this.app});

  final FileMetadata fileMetadata;
  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isSignedIn = ref.watch(Signer.activePubkeyProvider) != null;
    final textController = useTextEditingController();
    // Rebuild on text changes so the Post button enables/disables correctly
    useListenable(textController);

    final installedVersion = app.installedPackage?.version;
    final versionToComment = installedVersion ?? fileMetadata.version;
    final appName = app.name ?? 'this app';

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
                  style: context.textTheme.titleMedium,
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
        context.showError('Failed to post comment', technicalDetails: '$e');
      }
    }
  }
}

/// Reply composer for threaded comments
class _ReplyComposer extends HookConsumerWidget {
  const _ReplyComposer({
    required this.parentComment,
    this.app,
    this.fileMetadata,
    this.stack,
  });

  final Comment parentComment;
  final App? app;
  final FileMetadata? fileMetadata;
  final AppStack? stack;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isSignedIn = ref.watch(Signer.activePubkeyProvider) != null;
    final textController = useTextEditingController();
    useListenable(textController);

    // Query parent author profile
    final parentAuthorState = ref.watch(
      query<Profile>(
        authors: {parentComment.pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          stream: false,
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    final parentAuthor = switch (parentAuthorState) {
      StorageData(:final models) => models.firstOrNull,
      _ => null,
    };

    final replyingToName = parentAuthor?.nameOrNpub ?? 'comment';

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
                Expanded(
                  child: Text(
                    'Reply to $replyingToName',
                    style: context.textTheme.titleMedium,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
            // Show parent comment preview
            Container(
              margin: const EdgeInsets.only(top: 8, bottom: 16),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context)
                    .colorScheme
                    .surfaceContainerHighest
                    .withValues(alpha: 0.5),
                borderRadius: BorderRadius.circular(8),
                border: Border(
                  left: BorderSide(
                    color: Theme.of(context).colorScheme.outline,
                    width: 3,
                  ),
                ),
              ),
              child: Text(
                parentComment.content,
                style: context.textTheme.bodySmall?.copyWith(
                  color: Theme.of(context)
                      .colorScheme
                      .onSurface
                      .withValues(alpha: 0.7),
                ),
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (!isSignedIn) ...[
              const SignInPrompt(
                message: 'Sign in to reply to this comment.',
              ),
            ] else ...[
              TextField(
                controller: textController,
                decoration: const InputDecoration(
                  hintText: 'Write your reply...',
                  border: OutlineInputBorder(),
                ),
                maxLines: 4,
                autofocus: true,
              ),
            ],
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: AsyncButtonBuilder(
                child: const Text('Post Reply'),
                onPressed: () =>
                    _publishReply(ref, textController.text, context),
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

  Future<void> _publishReply(
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
            description: 'You need to sign in with Amber to post replies.',
          );
        }
        return;
      }

      // Determine root model (App or AppStack)
      final Model rootModel;
      if (app != null) {
        rootModel = app!;
      } else if (stack != null) {
        rootModel = stack!;
      } else {
        if (context.mounted) {
          context.showError(
            'Unable to reply',
            description: 'Could not determine the root content.',
          );
        }
        return;
      }

      final reply = PartialComment(
        content: content.trim(),
        rootModel: rootModel,
        parentModel: parentComment,
      );

      // Add v tag for version if available (for App comments)
      if (fileMetadata != null) {
        final installedVersion = app?.installedPackage?.version;
        final versionToComment = installedVersion ?? fileMetadata!.version;
        reply.event.addTagValue('v', versionToComment);
      }

      final signedReply = await reply.signWith(signer);

      await signedReply.save();
      await signedReply.publish(source: RemoteSource(relays: 'social'));

      if (context.mounted) {
        Navigator.pop(context);
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to post reply', technicalDetails: '$e');
      }
    }
  }
}

/// Comment composer for Stack comments
class _StackCommentComposer extends HookConsumerWidget {
  const _StackCommentComposer({required this.stack});

  final AppStack stack;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isSignedIn = ref.watch(Signer.activePubkeyProvider) != null;
    final textController = useTextEditingController();
    useListenable(textController);

    final stackName = stack.name ?? stack.identifier;

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
                  'Comment on $stackName',
                  style: context.textTheme.titleMedium,
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
                message: 'Sign in to share your thoughts about this stack.',
              ),
            ] else ...[
              TextField(
                controller: textController,
                decoration: InputDecoration(
                  hintText: 'Share your thoughts about $stackName...',
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

      final comment = PartialComment(content: content.trim(), rootModel: stack);

      final signedComment = await comment.signWith(signer);

      await signedComment.save();
      await signedComment.publish(source: RemoteSource(relays: 'social'));

      if (context.mounted) {
        Navigator.pop(context);
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to post comment', technicalDetails: '$e');
      }
    }
  }
}
