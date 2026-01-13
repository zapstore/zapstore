import 'package:flutter/material.dart';
import 'package:skeletonizer/skeletonizer.dart';
import 'package:models/models.dart';
import '../theme.dart';
import 'common/profile_avatar.dart';
import 'common/profile_name_widget.dart';

/// Profile header widget with skeleton loading state
/// Shows avatar + name, with npub overlay during loading
class ProfileHeader extends StatelessWidget {
  const ProfileHeader({
    super.key,
    required this.pubkey,
    this.profile,
    this.isLoading = false,
    this.radius = 40,
    this.showBio = true,
    this.additionalInfo,
  });

  final String pubkey;
  final Profile? profile;
  final bool isLoading;
  final double radius;
  final bool showBio;
  final Widget? additionalInfo;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          // Profile avatar (handles null profile with fallback)
          ProfileAvatar(profile: profile, radius: radius),
          const SizedBox(height: 16),

          // Display name with loading state
          ProfileNameWidget(
            pubkey: pubkey,
            profile: profile,
            isLoading: isLoading,
            style: Theme.of(context).textTheme.headlineSmall,
            skeletonWidth: 200,
          ),

          // Bio skeleton during loading
          if (isLoading && showBio) ...[
            const SizedBox(height: 8),
            SkeletonizerConfig(
              data: AppColors.getSkeletonizerConfig(Theme.of(context).brightness),
              child: Skeletonizer(
                enabled: true,
                child: Column(
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
              ),
            ),
          ],

          if (additionalInfo != null) ...[
            const SizedBox(height: 16),
            additionalInfo!,
          ],
        ],
      ),
    );
  }
}
