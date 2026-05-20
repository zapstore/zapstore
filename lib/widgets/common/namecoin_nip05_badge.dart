import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/namecoin/namecoin_nip05_service.dart';

/// Renders the NIP-05 row for a profile, with verification state
/// against the Namecoin blockchain when the identifier ends in
/// `.bit` (or uses the `d/` / `id/` prefixes).
///
/// Behaviour:
///   * Non-`.bit` identifiers fall through to a plain rendering with
///     no verification claim \u2014 same as today's `ProfileIdentityRow`.
///   * `.bit` identifiers show a loading spinner while the chain is
///     queried, then one of:
///       * Verified \u2014 green check, "verified on Namecoin"
///       * Mismatch \u2014 red warning, "on-chain key does not match"
///       * Unverified \u2014 amber dot, "name unregistered / expired"
///       * Unreachable \u2014 grey dot, "could not reach the chain"
///
/// Settings gate: the service is queried only when [enabled] is true
/// \u2014 lets the settings layer turn this off globally for users who
/// don't want chain traffic from their device.
class NamecoinNip05Badge extends ConsumerWidget {
  const NamecoinNip05Badge({
    super.key,
    required this.identifier,
    required this.claimedPubkey,
    this.enabled = true,
  });

  /// The `nip05` field as it appears on the profile.
  final String identifier;

  /// The hex pubkey claimed by the kind:0 event the profile came from.
  final String claimedPubkey;

  /// When false, renders the plain non-verifying row \u2014 useful for the
  /// global settings toggle.
  final bool enabled;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isBit = NamecoinNip05Service.isApplicable(identifier);

    if (!enabled || !isBit) {
      return _Row(
        identifier: identifier,
        icon: Icons.verified,
        iconColor: Theme.of(context).colorScheme.onSurfaceVariant,
        tooltip: isBit
            ? 'Namecoin verification disabled in settings'
            : null,
      );
    }

    final state = ref.watch(
      namecoinNip05VerificationProvider(
        (identifier: identifier, claimedPubkey: claimedPubkey),
      ),
    );

    return state.when(
      loading: () => _Row(
        identifier: identifier,
        icon: Icons.hourglass_top,
        iconColor: Theme.of(context).colorScheme.onSurfaceVariant,
        tooltip: 'Resolving on Namecoin\u2026',
        showSpinner: true,
      ),
      error: (err, _) => _Row(
        identifier: identifier,
        icon: Icons.cloud_off,
        iconColor: Theme.of(context).colorScheme.onSurfaceVariant,
        tooltip: 'Could not reach Namecoin: $err',
      ),
      data: (s) => switch (s) {
        NamecoinNip05Verified() => _Row(
            identifier: identifier,
            icon: Icons.verified,
            iconColor: Colors.green,
            tooltip: 'Verified on Namecoin (${s.namecoinName})',
          ),
        NamecoinNip05Mismatch() => _Row(
            identifier: identifier,
            icon: Icons.warning_amber_rounded,
            iconColor: Theme.of(context).colorScheme.error,
            tooltip:
                'On-chain key (${_short(s.onChainPubkey)}) does not match this profile (${_short(s.claimedPubkey)})',
          ),
        NamecoinNip05Unverified() => _Row(
            identifier: identifier,
            icon: Icons.help_outline,
            iconColor: Theme.of(context).colorScheme.tertiary,
            tooltip: 'Not verified on Namecoin: ${s.reason}',
          ),
        NamecoinNip05Unreachable() => _Row(
            identifier: identifier,
            icon: Icons.cloud_off,
            iconColor: Theme.of(context).colorScheme.onSurfaceVariant,
            tooltip: 'Could not reach Namecoin',
          ),
        NamecoinNip05NotApplicable() ||
        NamecoinNip05Resolving() =>
          _Row(
            identifier: identifier,
            icon: Icons.verified,
            iconColor: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
      },
    );
  }

  static String _short(String hex) =>
      '${hex.substring(0, 8)}\u2026${hex.substring(hex.length - 6)}';
}

class _Row extends StatelessWidget {
  const _Row({
    required this.identifier,
    required this.icon,
    required this.iconColor,
    this.tooltip,
    this.showSpinner = false,
  });

  final String identifier;
  final IconData icon;
  final Color iconColor;
  final String? tooltip;
  final bool showSpinner;

  @override
  Widget build(BuildContext context) {
    final row = Row(
      children: [
        if (showSpinner) ...[
          const SizedBox(
            width: 14,
            height: 14,
            child: CircularProgressIndicator(strokeWidth: 1.6),
          ),
          const SizedBox(width: 6),
        ] else ...[
          Icon(icon, size: 16, color: iconColor),
          const SizedBox(width: 4),
        ],
        Expanded(
          child: GestureDetector(
            onLongPress: () {
              Clipboard.setData(ClipboardData(text: identifier));
            },
            child: Text(
              identifier,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ),
      ],
    );
    if (tooltip == null) return row;
    return Tooltip(message: tooltip!, child: row);
  }
}
