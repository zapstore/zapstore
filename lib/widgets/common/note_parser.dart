import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:models/models.dart';
import 'package:zapstore/utils/extensions.dart';
import 'package:zapstore/services/profile_service.dart';
import 'package:any_link_preview/any_link_preview.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:zapstore/utils/url_utils.dart';

/// Enum for different media types
enum MediaType { image, video, audio, none }

/// Helper function to launch URLs with robust error handling
Future<void> _launchUrlSafely(String url, {String? context}) async {
  try {
    // Clean and validate the URL
    String cleanUrl = url.trim();
    if (!cleanUrl.startsWith('http://') && !cleanUrl.startsWith('https://')) {
      cleanUrl = 'https://$cleanUrl';
    }

    final uri = Uri.parse(cleanUrl);

    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } else {
      // Try alternative launch mode
      try {
        await launchUrl(uri, mode: LaunchMode.platformDefault);
      } catch (e2) {
        // Silently fail - both launch modes failed
      }
    }
  } catch (e) {
    // Silently handle URL launch errors
  }
}

/// A utility for parsing Nostr note content and replacing entities with custom widgets.
class NoteParser {
  // Regex patterns for different content types
  static final RegExp nip19Regex = RegExp(
    r'(?:nostr:)?(npub|nsec|note|nprofile|nevent|naddr|nrelay)1[02-9ac-hj-np-z]+',
    caseSensitive: false,
  );

  static final RegExp httpUrlPattern = RegExp(
    r'https?://[^\s<>"\[\]{}|\\^`]+',
    caseSensitive: false,
  );

  // Hashtag pattern - matches #word (letters, numbers, underscores)
  static final RegExp _hashtagPattern = RegExp(
    r'#[a-zA-Z0-9_]+',
    caseSensitive: false,
  );

  // Image file extensions
  static final Set<String> _imageExtensions = {
    'jpg',
    'jpeg',
    'png',
    'gif',
    'webp',
    'bmp',
    'svg',
  };

  // Video file extensions
  static final Set<String> _videoExtensions = {
    'mp4',
    'webm',
    'avi',
    'mov',
    'mkv',
  };

  // Audio file extensions (for future use)
  static final Set<String> _audioExtensions = {'mp3', 'wav', 'ogg'};

  /// Parses note content and returns a RichText widget with custom entity replacements.
  ///
  /// [context] - The build context for accessing theme
  /// [content] - The note text content to parse
  /// [onNostrEntity] - Optional callback for replacing NIP-19 entities (npub, note, etc.)
  /// [onHttpUrl] - Optional callback for replacing HTTP URLs
  /// [onMediaUrl] - Optional callback specifically for media URLs (images, videos, etc.)
  /// [onHashtag] - Optional callback for replacing hashtags (#hashtag)
  /// [onHashtagTap] - Optional callback for when a hashtag is tapped
  /// [onProfileTap] - Optional callback for when a profile is tapped
  /// [textStyle] - Default text style for regular text
  /// [linkStyle] - Text style for unhandled links (when callback returns null)
  static Widget parse(
    BuildContext context,
    String content, {
    Widget? Function(String entity)? onNostrEntity,
    Widget? Function(String httpUrl)? onHttpUrl,
    Widget? Function(String mediaUrl)? onMediaUrl,
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

    // Find all NIP-19 entities
    for (final match in nip19Regex.allMatches(content)) {
      final entity = match.group(0)!;
      final nip19Entity = entity.replaceFirst('nostr:', '');

      matches.add(
        _EntityMatch(
          start: match.start,
          end: match.end,
          text: entity,
          type: _EntityType.nip19,
          cleanEntity: nip19Entity,
        ),
      );
    }

    // Find all HTTP URLs
    for (final match in httpUrlPattern.allMatches(content)) {
      final url = match.group(0)!;
      final isMedia = _isMediaUrl(url);

      matches.add(
        _EntityMatch(
          start: match.start,
          end: match.end,
          text: url,
          type: isMedia ? _EntityType.media : _EntityType.http,
          cleanEntity: url,
        ),
      );
    }

    // Find all hashtags
    for (final match in _hashtagPattern.allMatches(content)) {
      final hashtag = match.group(0)!;

      matches.add(
        _EntityMatch(
          start: match.start,
          end: match.end,
          text: hashtag,
          type: _EntityType.hashtag,
          cleanEntity: hashtag.substring(1), // Remove the # symbol
        ),
      );
    }

    // Sort matches by position
    matches.sort((a, b) => a.start.compareTo(b.start));

    // Build spans
    int currentPos = 0;

    for (final match in matches) {
      // Add text before this match
      if (match.start > currentPos) {
        final textBefore = content.substring(currentPos, match.start);
        spans.add(TextSpan(text: textBefore, style: textStyle));
      }

      // Handle the entity
      Widget? replacement;

      switch (match.type) {
        case _EntityType.nip19:
          replacement = onNostrEntity?.call(match.cleanEntity);
          break;
        case _EntityType.media:
          replacement =
              onMediaUrl?.call(match.text) ?? onHttpUrl?.call(match.text);
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
        spans.add(
          WidgetSpan(
            child: replacement,
            alignment: PlaceholderAlignment.baseline,
            baseline: TextBaseline.alphabetic,
          ),
        );
      } else {
        // Fallback to styled text
        final style =
            linkStyle ??
            textStyle?.copyWith(
              color: Theme.of(context).colorScheme.primary,
              decoration: TextDecoration.underline,
            );
        spans.add(TextSpan(text: match.text, style: style));
      }

      currentPos = match.end;
    }

    // Add remaining text
    if (currentPos < content.length) {
      final remainingText = content.substring(currentPos);
      spans.add(TextSpan(text: remainingText, style: textStyle));
    }

    // If no entities found, return simple text
    if (spans.isEmpty) {
      return Text(content, style: textStyle);
    }

    return RichText(text: TextSpan(children: spans));
  }

  /// Checks if a URL is likely a media URL and returns the media type
  static MediaType _getMediaType(String url) {
    try {
      final uri = Uri.parse(url);
      final path = uri.path.toLowerCase();
      final extension = path.split('.').last;

      if (_imageExtensions.contains(extension)) {
        return MediaType.image;
      } else if (_videoExtensions.contains(extension)) {
        return MediaType.video;
      } else if (_audioExtensions.contains(extension)) {
        return MediaType.audio;
      } else {
        return MediaType.none;
      }
    } catch (e) {
      return MediaType.none;
    }
  }

  /// Checks if a URL is likely a media URL based on file extension
  static bool _isMediaUrl(String url) {
    return _getMediaType(url) != MediaType.none;
  }

  /// Validates if a string is a valid NIP-19 entity
  static bool isValidNip19Entity(String entity) {
    try {
      Utils.decodeShareableIdentifier(entity);
      return true;
    } catch (e) {
      return false;
    }
  }

  /// Extracts all NIP-19 entities from text
  @protected
  @visibleForTesting
  static List<String> extractNip19Entities(String content) {
    return nip19Regex
        .allMatches(content)
        .map((match) => match.group(0)!.replaceFirst('nostr:', ''))
        .where((entity) => isValidNip19Entity(entity))
        .toList();
  }

  /// Extracts all HTTP URLs from text
  @protected
  @visibleForTesting
  static List<String> extractHttpUrls(String content) {
    return httpUrlPattern
        .allMatches(content)
        .map((match) => match.group(0)!)
        .toList();
  }

  /// Extracts all media URLs from text
  @protected
  @visibleForTesting
  static List<String> extractMediaUrls(String content) {
    return extractHttpUrls(content).where((url) => _isMediaUrl(url)).toList();
  }

  /// Extracts all hashtags from text (without the # symbol)
  @protected
  @visibleForTesting
  static List<String> extractHashtags(String content) {
    return _hashtagPattern
        .allMatches(content)
        .map((match) => match.group(0)!.substring(1)) // Remove # symbol
        .toList();
  }
}

/// Internal class for tracking entity matches
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

/// Internal enum for entity types
enum _EntityType { nip19, http, media, hashtag }

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
    final profileAsync = ref.watch(profileProvider(profileData.pubkey));

    // Show animated npub while profile is being loaded
    if (profileAsync.isLoading || (!profileAsync.hasValue)) {
      return GestureDetector(
        onTap: onProfileTap != null
            ? () => onProfileTap!(profileData.pubkey)
            : null,
        child: _AnimatedLoadingChip(
          text: 'npub1${profileData.pubkey.substring(0, 8)}...',
          colorPair: colorPair,
        ),
      );
    }

    final profile = profileAsync.value;
    final displayName =
        profile?.nameOrNpub ?? '${profileData.pubkey.substring(0, 8)}...';

    return GestureDetector(
      onTap: onProfileTap != null
          ? () => onProfileTap!(profileData.pubkey)
          : null,
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
                      color: Theme.of(context)
                          .colorScheme
                          .onSurface
                          .withValues(alpha: 0.7),
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
        // Handle address navigation
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

class UrlChipWidget extends StatelessWidget {
  final String url;
  final List<Color> colorPair;

  const UrlChipWidget({super.key, required this.url, required this.colorPair});

  @override
  Widget build(BuildContext context) {
    // Fallback widget for when preview fails
    Widget fallbackWidget = GestureDetector(
      onTap: () => _launchUrlSafely(url, context: 'Fallback'),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colorPair[0].withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(4.0),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4.0),
          child: Text(
            url,
            style: context.textTheme.bodyMedium!.copyWith(
              color: colorPair[0],
              decoration: TextDecoration.underline,
            ),
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ),
    );

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4.0),
      child: Material(
        color: Colors.transparent,
        child: AnyLinkPreview(
          link: url,
          displayDirection: UIDirection.uiDirectionHorizontal,
          showMultimedia: true,
          bodyMaxLines: 2,
          bodyTextOverflow: TextOverflow.ellipsis,
          titleStyle: context.textTheme.bodyMedium!.copyWith(
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
          bodyStyle: context.textTheme.bodySmall!.copyWith(
            color: Colors.grey[600],
            fontSize: 12,
          ),
          cache: Duration.zero,
          backgroundColor: colorPair[0].withValues(alpha: 0.05),
          borderRadius: 8.0,
          removeElevation: true,
          boxShadow: const [],
          onTap: () => _launchUrlSafely(url, context: 'Link Preview'),
          errorWidget: fallbackWidget,
          errorBody: 'Link preview unavailable',
          errorTitle: 'Unable to load preview',
          errorImage: '',
          placeholderWidget: fallbackWidget,
        ),
      ),
    );
  }
}

class MediaWidget extends StatelessWidget {
  final String url;
  final List<Color> colorPair;

  const MediaWidget({super.key, required this.url, required this.colorPair});

  @override
  Widget build(BuildContext context) {
    final mediaType = NoteParser._getMediaType(url);

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8.0),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12.0),
        border: Border.all(color: colorPair[0].withValues(alpha: 0.2)),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12.0),
        child: switch (mediaType) {
          MediaType.image => _buildImageWidget(context),
          _ => _buildUnsupportedWidget(context),
        },
      ),
    );
  }

  Widget _buildImageWidget(BuildContext context) {
    final sanitizedUrl = sanitizeHttpUrl(url);
    if (sanitizedUrl == null) {
      return _buildUnsupportedWidget(context);
    }

    return CachedNetworkImage(
      imageUrl: sanitizedUrl,
      fit: BoxFit.cover,
      placeholder: (context, url) =>
          SizedBox(height: 200, child: _buildContentImageLoader(context)),
      errorWidget: (context, url, error) => SizedBox(
        height: 60,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.broken_image_outlined, color: Colors.grey[400]),
            const SizedBox(width: 8.0),
            Text(
              'Image failed to load',
              style: Theme.of(
                context,
              ).textTheme.bodySmall!.copyWith(color: Colors.grey[600]),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildContentImageLoader(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF2A2A2A), Color(0xFF3A3A3A), Color(0xFF2A2A2A)],
          stops: [0.0, 0.5, 1.0],
        ),
      ),
    );
  }

  Widget _buildUnsupportedWidget(BuildContext context) {
    return Container(
      height: 60,
      color: colorPair[0].withValues(alpha: 0.1),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.help_outline, color: colorPair[0]),
          const SizedBox(width: 8.0),
          Text(
            'Unsupported media type',
            style: Theme.of(
              context,
            ).textTheme.bodySmall!.copyWith(color: colorPair[0]),
          ),
        ],
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

/// Animated loading chip for profile loading states
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
