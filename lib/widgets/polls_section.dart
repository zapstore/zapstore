import 'package:async_button_builder/async_button_builder.dart';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:models/models.dart';
import 'package:zapstore/services/notification_service.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/widgets/auth_widgets.dart';
import 'package:zapstore/widgets/common/profile_avatar.dart';
import 'package:zapstore/widgets/common/profile_name_widget.dart';

/// Zapstore team npubs authorized to create polls on any app
const _zapstoreTeamNpubs = {
  'npub10r8xl2njyepcw2zwv3a6dyufj4e4ajx86hz6v4ehu4gnpupxxp7stjt2p8',
  'npub1wf4pufsucer5va8g9p0rj5dnhvfeh6d8w0g6eayaep5dhps6rsgs43dgh9',
  'npub1zafcms4xya5ap9zr7xxr0jlrtrattwlesytn2s42030lzu0dwlzqpd26k5',
};

/// Polls section for App detail screen (NIP-88)
class PollsSection extends HookConsumerWidget {
  const PollsSection({super.key, required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Only show polls from app developer or zapstore team
    final authorizedPubkeys = {app.pubkey, ..._zapstoreTeamNpubs};

    // Query polls tagged to this app from authorized authors
    final pollsState = ref.watch(
      query<Poll>(
        authors: authorizedPubkeys,
        tags: {
          '#a': {app.id},
        },
        source: LocalAndRemoteSource(stream: true, relays: 'social'),
      ),
    );

    // Extract polls and error state
    final List<Poll> polls = switch (pollsState) {
      StorageData(:final models) => models,
      _ => [],
    };
    final errorException = switch (pollsState) {
      StorageError(:final exception) => exception,
      _ => null,
    };
    final isLoading = pollsState is StorageLoading && polls.isEmpty;

    return _PollsSectionLayout(
      polls: polls,
      errorException: errorException,
      isLoading: isLoading,
      app: app,
    );
  }
}

/// Check if a pubkey is authorized to create polls on an app
bool _canCreatePoll(String? signedInPubkey, App app) {
  if (signedInPubkey == null) return false;
  // App developer can create polls on their own app
  if (signedInPubkey == app.pubkey) return true;
  // Zapstore team can create polls on any app
  // Convert npubs to hex for comparison
  final zapstoreTeamHex = _zapstoreTeamNpubs.map((npub) {
    try {
      return Utils.decodeShareableToString(npub);
    } catch (_) {
      return npub;
    }
  }).toSet();
  return zapstoreTeamHex.contains(signedInPubkey);
}

/// Layout for polls section
class _PollsSectionLayout extends ConsumerWidget {
  const _PollsSectionLayout({
    required this.polls,
    required this.errorException,
    required this.isLoading,
    required this.app,
  });

  final List<Poll> polls;
  final Object? errorException;
  final bool isLoading;
  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedInPubkey = ref.watch(Signer.activePubkeyProvider);
    final canCreate = _canCreatePoll(signedInPubkey, app);

    // Sort polls by creation date (newest first), expired polls at end
    final sortedPolls = [...polls]..sort((a, b) {
        // Expired polls go to the end
        if (a.isExpired && !b.isExpired) return 1;
        if (!a.isExpired && b.isExpired) return -1;
        // Otherwise sort by creation date (newest first)
        return b.createdAt.compareTo(a.createdAt);
      });

    // Show section if: has polls, is loading, has error, OR user can create polls
    if (sortedPolls.isEmpty && !isLoading && errorException == null && !canCreate) {
      return const SizedBox.shrink();
    }

    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('Polls', style: Theme.of(context).textTheme.titleLarge),
              if (sortedPolls.isNotEmpty) _PollCountBadge(count: sortedPolls.length),
            ],
          ),
          const SizedBox(height: 12),
          // Create Poll button for authorized users
          if (canCreate) ...[
            _CreatePollButton(app: app),
            const SizedBox(height: 12),
          ],
          // Loading state
          if (isLoading) ...[
            const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ),
            ),
          ],
          // Error message if any
          if (errorException != null) ...[
            _buildPollsError(context, errorException!),
          ],
          // Polls list
          if (sortedPolls.isNotEmpty) ...[
            ...sortedPolls.map(
              (poll) => Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: _PollCard(poll: poll, app: app),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildPollsError(BuildContext context, Object exception) {
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
              'Failed to load polls',
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

class _PollCountBadge extends StatelessWidget {
  const _PollCountBadge({required this.count});

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

/// Individual poll card with voting UI
class _PollCard extends HookConsumerWidget {
  const _PollCard({required this.poll, required this.app});

  final Poll poll;
  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isSignedIn = ref.watch(Signer.activePubkeyProvider) != null;
    final currentUserPubkey = ref.watch(Signer.activePubkeyProvider);

    // Query author profile
    final authorState = ref.watch(
      query<Profile>(
        authors: {poll.pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          stream: false,
          cachedFor: Duration(hours: 2),
        ),
      ),
    );
    final author = authorState.models.firstOrNull;
    final isAuthorLoading = authorState is StorageLoading && author == null;

    // Query all responses for this poll
    final responsesState = ref.watch(
      query<PollResponse>(
        tags: {
          '#e': {poll.event.id},
        },
        source: LocalAndRemoteSource(stream: true, relays: 'social'),
      ),
    );

    final List<PollResponse> allResponses = switch (responsesState) {
      StorageData(:final models) => models,
      _ => [],
    };

    // Deduplicate: one vote per pubkey (latest wins)
    final responsesByPubkey = <String, PollResponse>{};
    for (final response in allResponses) {
      final existing = responsesByPubkey[response.pubkey];
      if (existing == null || response.createdAt.isAfter(existing.createdAt)) {
        responsesByPubkey[response.pubkey] = response;
      }
    }
    final uniqueResponses = responsesByPubkey.values.toList();

    // Find current user's vote
    final userVote = currentUserPubkey != null
        ? responsesByPubkey[currentUserPubkey]
        : null;

    // Calculate vote counts per option
    final voteCounts = <String, int>{};
    for (final option in poll.options) {
      voteCounts[option.id] = 0;
    }
    for (final response in uniqueResponses) {
      for (final optionId in response.selectedOptionIds) {
        voteCounts[optionId] = (voteCounts[optionId] ?? 0) + 1;
      }
    }

    final totalVotes = uniqueResponses.length;
    final isExpired = poll.isExpired;

    // Track selected options for voting (before submission)
    final selectedOptions = useState<Set<String>>(
      userVote?.selectedOptionIds ?? {},
    );

    // Update selected options when user vote changes
    useEffect(() {
      if (userVote != null) {
        selectedOptions.value = userVote.selectedOptionIds;
      }
      return null;
    }, [userVote?.event.id]);

    return Container(
      decoration: BoxDecoration(
        color: Theme.of(context).cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: Theme.of(context).colorScheme.outline.withValues(alpha: 0.2),
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Poll header (author + date + expiry badge)
            Row(
              children: [
                ProfileAvatar(profile: author, radius: 14),
                const SizedBox(width: 8),
                Expanded(
                  child: ProfileNameWidget(
                    pubkey: poll.pubkey,
                    profile: author,
                    isLoading: isAuthorLoading,
                    style: context.textTheme.titleSmall,
                    skeletonWidth: 80,
                  ),
                ),
                if (isExpired)
                  Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: Colors.grey.shade600,
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Text(
                      'Ended',
                      style: context.textTheme.labelSmall?.copyWith(
                        color: Colors.white,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                const SizedBox(width: 8),
                Text(
                  DateFormat('MMM d, y').format(poll.createdAt),
                  style: context.textTheme.bodySmall?.copyWith(
                    color: Colors.grey[600],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            // Poll question
            Text(
              poll.content,
              style: context.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w600,
              ),
            ),
            // Poll type indicator
            if (poll.pollType == PollType.multiplechoice) ...[
              const SizedBox(height: 4),
              Text(
                'Select multiple options',
                style: context.textTheme.bodySmall?.copyWith(
                  color: Colors.grey[600],
                  fontStyle: FontStyle.italic,
                ),
              ),
            ],
            const SizedBox(height: 12),
            // Poll options
            ...poll.options.map((option) {
              final voteCount = voteCounts[option.id] ?? 0;
              final percentage =
                  totalVotes > 0 ? (voteCount / totalVotes * 100) : 0.0;
              final isSelected = selectedOptions.value.contains(option.id);
              final hasUserVoted = userVote != null;
              final userVotedForThis =
                  userVote?.selectedOptionIds.contains(option.id) ?? false;

              return Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _PollOptionButton(
                  option: option,
                  voteCount: voteCount,
                  percentage: percentage,
                  isSelected: isSelected,
                  isExpired: isExpired,
                  hasUserVoted: hasUserVoted,
                  userVotedForThis: userVotedForThis,
                  isSignedIn: isSignedIn,
                  isSingleChoice: poll.pollType == PollType.singlechoice,
                  onTap: isExpired
                      ? null
                      : () {
                          if (poll.pollType == PollType.singlechoice) {
                            // Single choice: replace selection
                            selectedOptions.value = {option.id};
                          } else {
                            // Multi choice: toggle
                            final current = Set<String>.from(selectedOptions.value);
                            if (current.contains(option.id)) {
                              current.remove(option.id);
                            } else {
                              current.add(option.id);
                            }
                            selectedOptions.value = current;
                          }
                        },
                ),
              );
            }),
            const SizedBox(height: 8),
            // Footer: vote count + end time + vote button
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '$totalVotes ${totalVotes == 1 ? 'vote' : 'votes'}',
                  style: context.textTheme.bodySmall?.copyWith(
                    color: Colors.grey[600],
                  ),
                ),
                if (poll.endsAt != null && !isExpired)
                  Text(
                    'Ends ${DateFormat('MMM d').format(poll.endsAt!)}',
                    style: context.textTheme.bodySmall?.copyWith(
                      color: Colors.grey[600],
                    ),
                  ),
              ],
            ),
            // Vote button
            if (!isExpired && selectedOptions.value.isNotEmpty) ...[
              const SizedBox(height: 12),
              _VoteButton(
                poll: poll,
                selectedOptionIds: selectedOptions.value,
                isSignedIn: isSignedIn,
                hasExistingVote: userVote != null,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Individual poll option button
class _PollOptionButton extends StatelessWidget {
  const _PollOptionButton({
    required this.option,
    required this.voteCount,
    required this.percentage,
    required this.isSelected,
    required this.isExpired,
    required this.hasUserVoted,
    required this.userVotedForThis,
    required this.isSignedIn,
    required this.isSingleChoice,
    required this.onTap,
  });

  final PollOption option;
  final int voteCount;
  final double percentage;
  final bool isSelected;
  final bool isExpired;
  final bool hasUserVoted;
  final bool userVotedForThis;
  final bool isSignedIn;
  final bool isSingleChoice;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final showResults = isExpired || hasUserVoted;
    final isHighlighted = isSelected || userVotedForThis;

    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: isHighlighted
                ? Theme.of(context).colorScheme.primary
                : Theme.of(context).colorScheme.outline.withValues(alpha: 0.3),
            width: isHighlighted ? 2 : 1,
          ),
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          children: [
            // Progress bar background (only when showing results)
            if (showResults)
              Positioned.fill(
                child: FractionallySizedBox(
                  alignment: Alignment.centerLeft,
                  widthFactor: percentage / 100,
                  child: Container(
                    color: isHighlighted
                        ? Theme.of(context)
                            .colorScheme
                            .primary
                            .withValues(alpha: 0.15)
                        : Theme.of(context)
                            .colorScheme
                            .surfaceContainerHighest
                            .withValues(alpha: 0.5),
                  ),
                ),
              ),
            // Content
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              child: Row(
                children: [
                  // Selection indicator
                  if (!isExpired) ...[
                    Icon(
                      isSingleChoice
                          ? (isSelected
                              ? Icons.radio_button_checked
                              : Icons.radio_button_unchecked)
                          : (isSelected
                              ? Icons.check_box
                              : Icons.check_box_outline_blank),
                      size: 20,
                      color: isSelected
                          ? Theme.of(context).colorScheme.primary
                          : Colors.grey[600],
                    ),
                    const SizedBox(width: 8),
                  ],
                  // Option label
                  Expanded(
                    child: Text(
                      option.label,
                      style: context.textTheme.bodyMedium?.copyWith(
                        fontWeight: isHighlighted ? FontWeight.w600 : null,
                      ),
                    ),
                  ),
                  // Vote count and percentage (when showing results)
                  if (showResults) ...[
                    Text(
                      '${percentage.toStringAsFixed(0)}%',
                      style: context.textTheme.bodySmall?.copyWith(
                        fontWeight: FontWeight.w600,
                        color: isHighlighted
                            ? Theme.of(context).colorScheme.primary
                            : null,
                      ),
                    ),
                    const SizedBox(width: 4),
                    Text(
                      '($voteCount)',
                      style: context.textTheme.bodySmall?.copyWith(
                        color: Colors.grey[600],
                      ),
                    ),
                  ],
                  // User voted indicator
                  if (userVotedForThis) ...[
                    const SizedBox(width: 8),
                    Icon(
                      Icons.check_circle,
                      size: 16,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Vote submission button
class _VoteButton extends HookConsumerWidget {
  const _VoteButton({
    required this.poll,
    required this.selectedOptionIds,
    required this.isSignedIn,
    required this.hasExistingVote,
  });

  final Poll poll;
  final Set<String> selectedOptionIds;
  final bool isSignedIn;
  final bool hasExistingVote;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (!isSignedIn) {
      return const SignInPrompt(
        message: 'Sign in to vote on this poll.',
      );
    }

    return SizedBox(
      width: double.infinity,
      child: AsyncButtonBuilder(
        child: Text(hasExistingVote ? 'Update Vote' : 'Submit Vote'),
        onPressed: () => _submitVote(ref, context),
        builder: (context, child, callback, buttonState) {
          return FilledButton(
            onPressed: buttonState.maybeWhen(
              loading: () => null,
              orElse: () => callback,
            ),
            child: buttonState.maybeWhen(
              loading: () => const SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                ),
              ),
              orElse: () => child,
            ),
          );
        },
      ),
    );
  }

  Future<void> _submitVote(WidgetRef ref, BuildContext context) async {
    try {
      final signer = ref.read(Signer.activeSignerProvider);
      if (signer == null) {
        if (context.mounted) {
          context.showError(
            'Sign in required',
            description: 'You need to sign in with Amber to vote.',
          );
        }
        return;
      }

      final response = PartialPollResponse(
        poll: poll,
        selectedOptionIds: selectedOptionIds,
      );

      final signedResponse = await response.signWith(signer);

      await signedResponse.save();
      await signedResponse.publish(source: RemoteSource(relays: 'social'));

      if (context.mounted) {
        context.showInfo(hasExistingVote ? 'Vote updated!' : 'Vote submitted!');
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to submit vote', description: '$e');
      }
    }
  }
}

/// Button to create a new poll
class _CreatePollButton extends StatelessWidget {
  const _CreatePollButton({required this.app});

  final App app;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      child: FilledButton.icon(
        onPressed: () => _showCreatePollComposer(context),
        icon: const Icon(Icons.poll_outlined),
        label: const Text('Create Poll'),
        style: FilledButton.styleFrom(
          backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
          foregroundColor: Theme.of(context).colorScheme.onSurface,
          padding: const EdgeInsets.symmetric(vertical: 12),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
      ),
    );
  }

  void _showCreatePollComposer(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (context) => _CreatePollComposer(app: app),
    );
  }
}

/// Modal for creating a new poll
class _CreatePollComposer extends HookConsumerWidget {
  const _CreatePollComposer({required this.app});

  final App app;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final questionController = useTextEditingController();
    final optionControllers = useState<List<TextEditingController>>([
      TextEditingController(),
      TextEditingController(),
    ]);
    final pollType = useState(PollType.singlechoice);
    final hasExpiration = useState(false);
    final expirationDays = useState(7);

    // Rebuild on text changes
    useListenable(questionController);
    for (final controller in optionControllers.value) {
      useListenable(controller);
    }

    // Clean up controllers on dispose
    useEffect(() {
      return () {
        for (final controller in optionControllers.value) {
          controller.dispose();
        }
      };
    }, []);

    final canSubmit = questionController.text.trim().isNotEmpty &&
        optionControllers.value
            .where((c) => c.text.trim().isNotEmpty)
            .length >= 2;

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Container(
        padding: const EdgeInsets.all(16),
        constraints: BoxConstraints(
          maxHeight: MediaQuery.of(context).size.height * 0.85,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Text(
                    'Create Poll for ${app.name ?? app.identifier}',
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
            const SizedBox(height: 16),
            // Scrollable content
            Flexible(
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Question input
                    TextField(
                      controller: questionController,
                      decoration: const InputDecoration(
                        labelText: 'Question',
                        hintText: 'What would you like to ask?',
                        border: OutlineInputBorder(),
                      ),
                      maxLines: 2,
                      autofocus: true,
                    ),
                    const SizedBox(height: 16),
                    // Options
                    Text(
                      'Options (minimum 2)',
                      style: context.textTheme.titleSmall,
                    ),
                    const SizedBox(height: 8),
                    ...optionControllers.value.asMap().entries.map((entry) {
                      final index = entry.key;
                      final controller = entry.value;
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: Row(
                          children: [
                            Expanded(
                              child: TextField(
                                controller: controller,
                                decoration: InputDecoration(
                                  labelText: 'Option ${index + 1}',
                                  border: const OutlineInputBorder(),
                                ),
                              ),
                            ),
                            if (optionControllers.value.length > 2)
                              IconButton(
                                onPressed: () {
                                  final list = [...optionControllers.value];
                                  list[index].dispose();
                                  list.removeAt(index);
                                  optionControllers.value = list;
                                },
                                icon: const Icon(Icons.remove_circle_outline),
                                color: Colors.red,
                              ),
                          ],
                        ),
                      );
                    }),
                    if (optionControllers.value.length < 10)
                      TextButton.icon(
                        onPressed: () {
                          optionControllers.value = [
                            ...optionControllers.value,
                            TextEditingController(),
                          ];
                        },
                        icon: const Icon(Icons.add),
                        label: const Text('Add Option'),
                      ),
                    const SizedBox(height: 16),
                    // Poll type
                    Text(
                      'Poll Type',
                      style: context.textTheme.titleSmall,
                    ),
                    const SizedBox(height: 8),
                    SegmentedButton<PollType>(
                      segments: const [
                        ButtonSegment(
                          value: PollType.singlechoice,
                          label: Text('Single Choice'),
                        ),
                        ButtonSegment(
                          value: PollType.multiplechoice,
                          label: Text('Multiple Choice'),
                        ),
                      ],
                      selected: {pollType.value},
                      onSelectionChanged: (selected) {
                        pollType.value = selected.first;
                      },
                    ),
                    const SizedBox(height: 16),
                    // Expiration
                    SwitchListTile(
                      title: const Text('Set Expiration'),
                      value: hasExpiration.value,
                      onChanged: (value) => hasExpiration.value = value,
                      contentPadding: EdgeInsets.zero,
                    ),
                    if (hasExpiration.value) ...[
                      Row(
                        children: [
                          const Text('Expires in '),
                          DropdownButton<int>(
                            value: expirationDays.value,
                            items: [1, 3, 7, 14, 30].map((days) {
                              return DropdownMenuItem(
                                value: days,
                                child: Text('$days ${days == 1 ? 'day' : 'days'}'),
                              );
                            }).toList(),
                            onChanged: (value) {
                              if (value != null) expirationDays.value = value;
                            },
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            // Submit button
            SizedBox(
              width: double.infinity,
              child: AsyncButtonBuilder(
                child: const Text('Create Poll'),
                onPressed: () => _createPoll(
                  ref,
                  context,
                  questionController.text,
                  optionControllers.value.map((c) => c.text).toList(),
                  pollType.value,
                  hasExpiration.value ? expirationDays.value : null,
                ),
                builder: (context, child, callback, buttonState) {
                  return FilledButton(
                    onPressed: !canSubmit
                        ? null
                        : buttonState.maybeWhen(
                            loading: () => null,
                            orElse: () => callback,
                          ),
                    child: buttonState.maybeWhen(
                      loading: () => const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
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

  Future<void> _createPoll(
    WidgetRef ref,
    BuildContext context,
    String question,
    List<String> optionTexts,
    PollType pollType,
    int? expirationDays,
  ) async {
    try {
      final signer = ref.read(Signer.activeSignerProvider);
      if (signer == null) {
        if (context.mounted) {
          context.showError(
            'Sign in required',
            description: 'You need to sign in with Amber to create polls.',
          );
        }
        return;
      }

      // Filter out empty options and create PollOption objects
      final options = optionTexts
          .where((text) => text.trim().isNotEmpty)
          .toList()
          .asMap()
          .entries
          .map((e) => PollOption(
                id: 'opt${e.key}',
                label: e.value.trim(),
              ))
          .toList();

      if (options.length < 2) {
        if (context.mounted) {
          context.showError('At least 2 options are required');
        }
        return;
      }

      // Calculate expiration
      DateTime? endsAt;
      if (expirationDays != null) {
        endsAt = DateTime.now().add(Duration(days: expirationDays));
      }

      final poll = PartialPoll(
        content: question.trim(),
        options: options,
        pollType: pollType,
        endsAt: endsAt,
        targetModel: app,
      );

      final signedPoll = await poll.signWith(signer);

      await signedPoll.save();
      await signedPoll.publish(source: RemoteSource(relays: 'social'));

      if (context.mounted) {
        Navigator.pop(context);
        context.showInfo('Poll created!');
      }
    } catch (e) {
      if (context.mounted) {
        context.showError('Failed to create poll', description: '$e');
      }
    }
  }
}
