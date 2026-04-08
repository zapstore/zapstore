import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:url_launcher/url_launcher.dart';

Future<void> _launchUrlSafely(String url) async {
  try {
    String cleanUrl = url.trim();
    if (!cleanUrl.startsWith('http://') && !cleanUrl.startsWith('https://')) {
      cleanUrl = 'https://$cleanUrl';
    }

    final uri = Uri.parse(cleanUrl);

    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } else {
      try {
        await launchUrl(uri, mode: LaunchMode.platformDefault);
      } catch (_) {}
    }
  } catch (_) {}
}

class NoteParser {
  static final RegExp nip19Regex = RegExp(
    r'(?:nostr:)?(npub|nsec|note|nprofile|nevent|naddr|nrelay)1[02-9ac-hj-np-z]+',
    caseSensitive: false,
  );

  static final RegExp httpUrlPattern = RegExp(
    r'https?://[^\s<>"\[\]{}|\\^`]+',
    caseSensitive: false,
  );

  static final RegExp _hashtagPattern = RegExp(
    r'#[a-zA-Z0-9_]+',
    caseSensitive: false,
  );

  static Widget parse(
    BuildContext context,
    String content, {
    Widget? Function(String entity)? onNostrEntity,
    Widget? Function(String httpUrl)? onHttpUrl,
    Widget? Function(String hashtag)? onHashtag,
    void Function(String hashtag)? onHashtagTap,
    void Function(String pubkey)? onProfileTap,
    TextStyle? textStyle,
    TextStyle? linkStyle,
  }) {
    if (content.isEmpty) {
      return Text('', style: textStyle);
    }

    final List<InlineSpan> spans = [];
    final List<_EntityMatch> matches = [];

    for (final match in nip19Regex.allMatches(content)) {
      final entity = match.group(0)!;
      final nip19Entity = entity.replaceFirst('nostr:', '');
      matches.add(_EntityMatch(
        start: match.start,
        end: match.end,
        text: entity,
        type: _EntityType.nip19,
        cleanEntity: nip19Entity,
      ));
    }

    for (final match in httpUrlPattern.allMatches(content)) {
      final url = match.group(0)!;
      matches.add(_EntityMatch(
        start: match.start,
        end: match.end,
        text: url,
        type: _EntityType.http,
        cleanEntity: url,
      ));
    }

    for (final match in _hashtagPattern.allMatches(content)) {
      final hashtag = match.group(0)!;
      matches.add(_EntityMatch(
        start: match.start,
        end: match.end,
        text: hashtag,
        type: _EntityType.hashtag,
        cleanEntity: hashtag.substring(1),
      ));
    }

    matches.sort((a, b) => a.start.compareTo(b.start));
    final filtered = <_EntityMatch>[];
    var lastEnd = -1;
    for (final m in matches) {
      if (m.start >= lastEnd) {
        filtered.add(m);
        lastEnd = m.end;
      }
    }

    int currentPos = 0;

    for (final match in filtered) {
      if (match.start > currentPos) {
        final textBefore = content.substring(currentPos, match.start);
        spans.add(TextSpan(text: textBefore, style: textStyle));
      }

      Widget? replacement;

      switch (match.type) {
        case _EntityType.nip19:
          replacement = onNostrEntity?.call(match.cleanEntity);
          break;
        case _EntityType.http:
          replacement = onHttpUrl?.call(match.text);
          break;
        case _EntityType.hashtag:
          replacement =
              onHashtag?.call(match.cleanEntity) ??
              HashtagWidget(
                hashtag: match.cleanEntity,
                colorPair: [
                  Theme.of(context).colorScheme.primary,
                  Theme.of(context).colorScheme.primaryContainer,
                ],
                onTap: onHashtagTap != null
                    ? () => onHashtagTap(match.cleanEntity)
                    : null,
              );
          break;
      }

      if (replacement != null) {
        spans.add(WidgetSpan(
          child: replacement,
          alignment: PlaceholderAlignment.baseline,
          baseline: TextBaseline.alphabetic,
        ));
      } else if (match.type == _EntityType.http) {
        final style =
            linkStyle ??
            textStyle?.copyWith(
              color: Theme.of(context).colorScheme.primary,
            );
        spans.add(TextSpan(
          text: match.text,
          style: style,
          recognizer: TapGestureRecognizer()
            ..onTap = () => _launchUrlSafely(match.text),
        ));
      } else {
        final style =
            linkStyle ??
            textStyle?.copyWith(
              color: Theme.of(context).colorScheme.primary,
            );
        spans.add(TextSpan(text: match.text, style: style));
      }

      currentPos = match.end;
    }

    if (currentPos < content.length) {
      final remainingText = content.substring(currentPos);
      spans.add(TextSpan(text: remainingText, style: textStyle));
    }

    if (spans.isEmpty) {
      return Text(content, style: textStyle);
    }

    return Text.rich(TextSpan(children: spans));
  }
}

class _EntityMatch {
  final int start;
  final int end;
  final String text;
  final _EntityType type;
  final String cleanEntity;

  _EntityMatch({
    required this.start,
    required this.end,
    required this.text,
    required this.type,
    required this.cleanEntity,
  });
}

enum _EntityType { nip19, http, hashtag }

// Widgets

class NostrEntityWidget extends StatelessWidget {
  final String entity;
  final List<Color> colorPair;
  final void Function(String pubkey)? onProfileTap;
  final void Function(String hashtag)? onHashtagTap;

  const NostrEntityWidget({
    super.key,
    required this.entity,
    required this.colorPair,
    this.onProfileTap,
    this.onHashtagTap,
  });

  @override
  Widget build(BuildContext context) {
    try {
      final decoded = Utils.decodeShareableIdentifier(entity);

      return switch (decoded) {
        ProfileData() => ProfileEntityWidget(
          profileData: decoded,
          colorPair: colorPair,
          onProfileTap: onProfileTap,
        ),
        EventData() => EventEntityWidget(
          eventData: decoded,
          colorPair: colorPair,
        ),
        AddressData() => AddressEntityWidget(
          addressData: decoded,
          colorPair: colorPair,
        ),
      };
    } catch (e) {
      return GenericNip19Widget(entity: entity, colorPair: colorPair);
    }
  }
}

class ProfileEntityWidget extends ConsumerWidget {
  final ProfileData profileData;
  final List<Color> colorPair;
  final void Function(String pubkey)? onProfileTap;

  const ProfileEntityWidget({
    super.key,
    required this.profileData,
    required this.colorPair,
    this.onProfileTap,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileState = ref.watch(
      query<Profile>(
        authors: {profileData.pubkey},
        source: const LocalAndRemoteSource(
          relays: {'social', 'vertex'},
          cachedFor: Duration(hours: 2),
        ),
        subscriptionPrefix: 'app-note-profile',
      ),
    );

    void handleTap() {
      if (onProfileTap != null) {
        onProfileTap!(profileData.pubkey);
      } else {
        final segments = GoRouterState.of(context).uri.pathSegments;
        final branch = segments.isNotEmpty ? segments.first : 'search';
        context.push('/$branch/user/${profileData.pubkey}');
      }
    }

    return switch (profileState) {
      StorageLoading() => GestureDetector(
        onTap: handleTap,
        child: _AnimatedLoadingChip(
          text: 'npub1${profileData.pubkey.substring(0, 8)}...',
          colorPair: colorPair,
        ),
      ),
      StorageError() || StorageData(models: []) => GestureDetector(
        onTap: handleTap,
        child: _AnimatedLoadingChip(
          text: 'npub1${profileData.pubkey.substring(0, 8)}...',
          colorPair: colorPair,
        ),
      ),
      StorageData(:final models) => _buildProfileWidget(context, models.first),
    };
  }

  Widget _buildProfileWidget(BuildContext context, Profile profile) {
    final displayName = profile.nameOrNpub;

    void handleTap() {
      if (onProfileTap != null) {
        onProfileTap!(profileData.pubkey);
      } else {
        final segments = GoRouterState.of(context).uri.pathSegments;
        final branch = segments.isNotEmpty ? segments.first : 'search';
        context.push('/$branch/user/${profileData.pubkey}');
      }
    }

    return GestureDetector(
      onTap: handleTap,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colorPair[0].withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(4.0),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4.0),
          child: Text(
            displayName,
            style: context.textTheme.bodyMedium!.copyWith(
              fontWeight: FontWeight.w500,
              color: colorPair[0],
            ),
          ),
        ),
      ),
    );
  }
}

class EventEntityWidget extends StatelessWidget {
  final EventData eventData;
  final List<Color> colorPair;

  const EventEntityWidget({
    super.key,
    required this.eventData,
    required this.colorPair,
  });

  @override
  Widget build(BuildContext context) {
    final shortId = eventData.eventId.length > 12
        ? '${eventData.eventId.substring(0, 8)}…'
        : eventData.eventId;

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8.0),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLowest,
        borderRadius: BorderRadius.circular(12.0),
        border: Border.all(
          color: colorPair[0].withValues(alpha: 0.2),
          width: 1.0,
        ),
        boxShadow: [
          BoxShadow(
            color: colorPair[0].withValues(alpha: 0.05),
            blurRadius: 4.0,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Row(
          children: [
            Icon(Icons.event, color: colorPair[0]),
            const SizedBox(width: 8.0),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Event',
                    style: context.textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    shortId,
                    style: context.textTheme.bodySmall?.copyWith(
                      color: Theme.of(
                        context,
                      ).colorScheme.onSurface.withValues(alpha: 0.7),
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class AddressEntityWidget extends StatelessWidget {
  final AddressData addressData;
  final List<Color> colorPair;

  const AddressEntityWidget({
    super.key,
    required this.addressData,
    required this.colorPair,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () {
        final segment = addressData.kind == 30267 ? 'stack' : 'app';
        final segments = GoRouterState.of(context).uri.pathSegments;
        final branch = segments.isNotEmpty ? segments.first : 'search';
        context.push('/$branch/$segment/${addressData.identifier}');
      },
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colorPair[0].withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(4.0),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4.0),
          child: Text(
            addressData.identifier,
            style: context.textTheme.bodyMedium!.copyWith(
              fontWeight: FontWeight.w500,
              color: colorPair[0],
            ),
          ),
        ),
      ),
    );
  }
}

class GenericNip19Widget extends StatelessWidget {
  final String entity;
  final List<Color> colorPair;

  const GenericNip19Widget({
    super.key,
    required this.entity,
    required this.colorPair,
  });

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: colorPair[0].withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(4.0),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4.0),
        child: Text(
          entity,
          style: context.textTheme.bodyMedium!.copyWith(
            fontWeight: FontWeight.w500,
            color: colorPair[0],
          ),
        ),
      ),
    );
  }
}

class HashtagWidget extends StatelessWidget {
  final String hashtag;
  final List<Color> colorPair;
  final VoidCallback? onTap;

  const HashtagWidget({
    super.key,
    required this.hashtag,
    required this.colorPair,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: colorPair[0].withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(4.0),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 4.0, vertical: 1.0),
        child: Text(
          '#$hashtag',
          style: context.textTheme.bodyMedium!.copyWith(
            color: colorPair[0],
            fontWeight: FontWeight.w500,
          ),
        ),
      ),
    );
  }
}

class _AnimatedLoadingChip extends StatefulWidget {
  final String text;
  final List<Color> colorPair;

  const _AnimatedLoadingChip({required this.text, required this.colorPair});

  @override
  State<_AnimatedLoadingChip> createState() => _AnimatedLoadingChipState();
}

class _AnimatedLoadingChipState extends State<_AnimatedLoadingChip>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _animation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      duration: const Duration(milliseconds: 1500),
      vsync: this,
    );
    _animation = Tween<double>(
      begin: 0.1,
      end: 0.3,
    ).animate(CurvedAnimation(parent: _controller, curve: Curves.easeInOut));
    _controller.repeat(reverse: true);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _animation,
      builder: (context, child) {
        return DecoratedBox(
          decoration: BoxDecoration(
            color: widget.colorPair[0].withValues(alpha: _animation.value),
            borderRadius: BorderRadius.circular(4.0),
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4.0),
            child: Text(
              widget.text,
              style: context.textTheme.bodyMedium!.copyWith(
                fontWeight: FontWeight.w500,
                color: widget.colorPair[0],
              ),
            ),
          ),
        );
      },
    );
  }
}
