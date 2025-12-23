import 'package:flutter/material.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:models/models.dart';
import '../theme.dart';
import 'common/profile_avatar.dart';

/// Profile header widget with skeleton loading state
class ProfileHeader extends StatelessWidget {
  const ProfileHeader({
    super.key,
    this.profile,
    this.isLoading = false,
    this.radius = 40,
    this.showBio = true,
    this.additionalInfo,
  });

  final Profile? profile;
  final bool isLoading;
  final double radius;
  final bool showBio;
  final Widget? additionalInfo;

  @override
  Widget build(BuildContext context) {
    if (isLoading || profile == null) {
      return _buildSkeleton(context);
    }

    return Container(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          // Profile avatar
          ProfileAvatar(profile: profile, radius: radius),
          const SizedBox(height: 16),

          // Display name
          Text(
            profile!.name ?? profile!.nameOrNpub,
            style: Theme.of(context).textTheme.headlineSmall,
            textAlign: TextAlign.center,
          ),

          if (additionalInfo != null) ...[
            const SizedBox(height: 16),
            additionalInfo!,
          ],
        ],
      ),
    );
  }

  Widget _buildSkeleton(BuildContext context) {
    return SkeletonizerConfig(
      data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
      child: Skeletonizer(
        enabled: true,
        child: Container(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              // Profile avatar skeleton
              CircleAvatar(
                radius: radius,
                backgroundColor: AppColors.darkSkeletonBase,
                child: Icon(
                  Icons.person,
                  size: radius,
                  color: AppColors.darkSkeletonHighlight,
                ),
              ),
              const SizedBox(height: 16),

              // Display name skeleton
              Container(
                height: 28,
                width: 200,
                decoration: BoxDecoration(
                  color: AppColors.darkSkeletonBase,
                  borderRadius: BorderRadius.circular(4),
                ),
              ),

              if (showBio) ...[
                const SizedBox(height: 8),
                // Bio skeleton
                Column(
                  children: List.generate(
                    2,
                    (index) => Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Container(
                        height: 16,
                        width: index == 1 ? 150 : double.infinity,
                        decoration: BoxDecoration(
                          color: AppColors.darkSkeletonBase,
                          borderRadius: BorderRadius.circular(4),
                        ),
                      ),
                    ),
                  ),
                ),
              ],

              if (additionalInfo != null) ...[
                const SizedBox(height: 16),
                Container(
                  height: 40,
                  width: double.infinity,
                  decoration: BoxDecoration(
                    color: AppColors.darkSkeletonBase,
                    borderRadius: BorderRadius.circular(4),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
