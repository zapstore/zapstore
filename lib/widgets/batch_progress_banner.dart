import 'package:flutter/material.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:zapstore/services/package_manager/package_manager.dart';
import 'package:zapstore/theme.dart';

/// Banner showing batch operation progress (e.g., during "Update All").
///
/// All state is fully derived from operations map - no parameters needed.
/// The key insight: operations stay in map as `Completed` state after success,
/// so we can derive total, completed, and in-progress counts directly.
class BatchProgressBanner extends ConsumerWidget {
  const BatchProgressBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final progress = ref.watch(batchProgressProvider);

    if (progress == null) return const SizedBox.shrink();

    return Container(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.darkPillBackground,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          // Spinner when in progress, checkmark when all complete
          if (progress.hasInProgress) ...[
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Colors.white,
              ),
            ),
            const SizedBox(width: 12),
          ] else if (progress.isAllComplete) ...[
            Icon(Icons.check_circle, size: 18, color: Colors.green.shade400),
            const SizedBox(width: 10),
          ],

          // Status text
          Expanded(
            child: Text(
              progress.statusText,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 14,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),

          // Failure count
          if (progress.failed > 0)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.red.shade700,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                '${progress.failed} failed',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
        ],
      ),
    );
  }
}
