import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:models/models.dart';
import 'package:url_launcher/url_launcher.dart';
import 'profile_avatar.dart';
import 'profile_name_widget.dart';

/// Horizontal profile identity row with avatar, name, npub, and nip05.
/// Used in profile screens for consistent display of user identity.
class ProfileIdentityRow extends StatelessWidget {
  const ProfileIdentityRow({
    super.key,
    required this.pubkey,
    this.profile,
    this.isLoading = false,
    this.avatarRadius = 32,
    this.onCopiedNpub,
  });

  final String pubkey;
  final Profile? profile;
  final bool isLoading;
  final double avatarRadius;
  final VoidCallback? onCopiedNpub;

  @override
  Widget build(BuildContext context) {
    final npub = Utils.encodeShareableFromString(pubkey, type: 'npub');
    final abbreviatedNpub =
        '${npub.substring(0, 12)}...${npub.substring(npub.length - 8)}';

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ProfileAvatar(profile: profile, pubkey: pubkey, radius: avatarRadius),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Name
              ProfileNameWidget(
                pubkey: pubkey,
                profile: profile,
                isLoading: isLoading,
                style: Theme.of(context).textTheme.titleMedium,
                skeletonWidth: 180,
              ),
              const SizedBox(height: 4),
              // Npub row with open and copy
              _NpubRow(
                npub: npub,
                abbreviatedNpub: abbreviatedNpub,
                onCopied: onCopiedNpub,
              ),
              // NIP-05
              if (profile?.nip05 != null) ...[
                const SizedBox(height: 4),
                Row(
                  children: [
                    Icon(
                      Icons.verified,
                      size: 16,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                    const SizedBox(width: 4),
                    Expanded(
                      child: Text(
                        profile!.nip05!,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _NpubRow extends StatelessWidget {
  const _NpubRow({
    required this.npub,
    required this.abbreviatedNpub,
    this.onCopied,
  });

  final String npub;
  final String abbreviatedNpub;
  final VoidCallback? onCopied;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Npub with external link
        GestureDetector(
          onTap: () => launchUrl(
            Uri.parse('https://npub.world/$npub'),
            mode: LaunchMode.externalApplication,
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.key,
                size: 16,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              const SizedBox(width: 4),
              Text(
                abbreviatedNpub,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurface,
                ),
              ),
              const SizedBox(width: 4),
              Icon(
                Icons.open_in_new,
                size: 12,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        // Copy button
        GestureDetector(
          onTap: () {
            Clipboard.setData(ClipboardData(text: npub));
            onCopied?.call();
          },
          child: Icon(
            Icons.copy,
            size: 12,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }
}
