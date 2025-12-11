import 'package:flutter/material.dart';

class EngagementRow extends StatelessWidget {
  final int likesCount;
  final int repostsCount;
  final int zapsCount;
  final int zapsSatAmount;
  final int? commentsCount;
  final VoidCallback? onLike;
  final VoidCallback? onRepost;
  final VoidCallback? onZap;
  final VoidCallback? onComment;
  final bool isLiked;
  final bool isReposted;
  final bool isZapped;
  final bool isLiking;
  final bool isReposting;
  final bool isZapping;

  const EngagementRow({
    super.key,
    required this.likesCount,
    required this.repostsCount,
    required this.zapsCount,
    required this.zapsSatAmount,
    this.commentsCount,
    this.onLike,
    this.onRepost,
    this.onZap,
    this.onComment,
    this.isLiked = false,
    this.isReposted = false,
    this.isZapped = false,
    this.isLiking = false,
    this.isReposting = false,
    this.isZapping = false,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    return Row(
      children: [
        // Comments (first now)
        if (commentsCount != null) ...[
          _EngagementItem(
            icon: Icons.mode_comment_outlined,
            activeIcon: Icons.mode_comment,
            count: commentsCount!,
            onTap: onComment,
            isActive: false,
            isLoading: false,
            activeColor: colorScheme.primary,
            theme: theme,
          ),
          const SizedBox(width: 24),
        ],

        // Likes
        _EngagementItem(
          icon: Icons.favorite_border,
          activeIcon: Icons.favorite,
          count: likesCount,
          onTap: onLike,
          isActive: isLiked,
          isLoading: isLiking,
          activeColor: const Color(0xFFE91E63), // Material Pink
          theme: theme,
        ),

        const SizedBox(width: 24),

        // Reposts
        _EngagementItem(
          icon: Icons.repeat,
          activeIcon: Icons.repeat,
          count: repostsCount,
          onTap: onRepost,
          isActive: isReposted,
          isLoading: isReposting,
          activeColor: const Color(0xFF4CAF50), // Material Green
          theme: theme,
        ),

        const SizedBox(width: 24),

        // Zaps
        _ZapItem(
          count: zapsCount,
          satAmount: zapsSatAmount,
          onTap: onZap,
          isActive: isZapped,
          isLoading: isZapping,
          theme: theme,
        ),
      ],
    );
  }
}

class _EngagementItem extends StatelessWidget {
  final IconData icon;
  final IconData? activeIcon;
  final int count;
  final VoidCallback? onTap;
  final bool isActive;
  final bool isLoading;
  final Color activeColor;
  final ThemeData theme;

  const _EngagementItem({
    required this.icon,
    this.activeIcon,
    required this.count,
    required this.onTap,
    required this.isActive,
    required this.isLoading,
    required this.activeColor,
    required this.theme,
  });

  @override
  Widget build(BuildContext context) {
    final color = isActive
        ? activeColor
        : theme.colorScheme.onSurface.withValues(alpha: 0.6);

    final displayIcon = isActive && activeIcon != null ? activeIcon! : icon;

    return InkWell(
      onTap: isLoading ? null : onTap,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            isLoading
                ? SizedBox(
                    width: 17,
                    height: 17,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: color,
                    ),
                  )
                : Icon(displayIcon, size: 17, color: color),
            if (count > 0) ...[
              const SizedBox(width: 4),
              Text(
                _formatCount(count),
                style: theme.textTheme.labelSmall?.copyWith(
                  color: color,
                  fontWeight: isActive ? FontWeight.w600 : FontWeight.normal,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _formatCount(int count) {
    if (count >= 1000000) {
      return '${(count / 1000000).toStringAsFixed(1)}M';
    } else if (count >= 1000) {
      return '${(count / 1000).toStringAsFixed(1)}K';
    }
    return count.toString();
  }
}

class _ZapItem extends StatelessWidget {
  final int count;
  final int satAmount;
  final VoidCallback? onTap;
  final bool isActive;
  final bool isLoading;
  final ThemeData theme;

  const _ZapItem({
    required this.count,
    required this.satAmount,
    required this.onTap,
    required this.isActive,
    required this.isLoading,
    required this.theme,
  });

  @override
  Widget build(BuildContext context) {
    final color = isActive
        ? const Color(0xFFFF9800) // Material Orange
        : theme.colorScheme.onSurface.withValues(alpha: 0.6);

    return InkWell(
      onTap: isLoading ? null : onTap,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            isLoading
                ? SizedBox(
                    width: 17,
                    height: 17,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: color,
                    ),
                  )
                : Icon(Icons.bolt, size: 17, color: color),
            if (count > 0 || satAmount > 0) ...[
              const SizedBox(width: 4),
              Text(
                satAmount > 0 ? _formatSats(satAmount) : count.toString(),
                style: theme.textTheme.labelSmall?.copyWith(
                  color: color,
                  fontWeight: isActive ? FontWeight.w600 : FontWeight.normal,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _formatSats(int sats) {
    if (sats >= 1000000) {
      return '${(sats / 1000000).toStringAsFixed(1)}M';
    } else if (sats >= 1000) {
      return '${(sats / 1000).toStringAsFixed(1)}K';
    }
    return sats.toString();
  }
}
