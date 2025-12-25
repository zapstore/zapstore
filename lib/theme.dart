import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:skeletonizer/skeletonizer.dart';

/// Base font family used for body text and UI elements
const kFontFamily = 'Inter';

/// Display and headline font family
const kHeadlineFontFamily = 'Inter Display';

// Professional color constants (dark theme only)
class AppColors {
  // Dark theme professional blues
  static const Color darkPrimary = Color(0xFF3A6FCC);
  static const Color darkSecondary = Color(0xFF4A8FCC);
  static const Color darkSurface = Color(
    0xFF0F141B,
  ); // slightly darker for cards/pills
  static const Color darkSurfaceVariant = Color(
    0xFF161C27,
  ); // darker container variant
  static const Color darkBackground = Color(0xFF0A0F1A); // from palette
  static const Color darkBackgroundSecondary = Color(
    0xFF111723,
  ); // from palette
  static const Color darkOnSurface = Color(0xFFE8EAED);
  static const Color darkOnSurfaceVariant = Color(0xFFB8BCC8);
  static const Color darkOnSurfaceSecondary = Color(
    0xFFC1C7CD,
  ); // Lighter text for descriptions and "Published by"
  static const Color darkOutline = Color(0xFF2D3748);

  // Dark skeleton loader colors (dark blue theme)
  static const Color darkSkeletonBase = Color(0xFF1E3A5F);
  static const Color darkSkeletonHighlight = Color(0xFF2B4A73);

  // App-specific pill backgrounds (slightly bluish)
  static const Color darkPillBackground = Color(0xFF213A60); // darker bluish

  // Primary action colors (used for buttons, badges, info notifications)
  static const Color darkActionPrimary = Color(
    0xFF2D5FAA,
  ); // darker professional blue for dark mode

  // Helper method to get skeleton colors
  static SkeletonizerConfigData getSkeletonizerConfig(Brightness brightness) {
    return SkeletonizerConfigData(
      effect: ShimmerEffect(
        baseColor: darkSkeletonBase,
        highlightColor: darkSkeletonHighlight,
        duration: const Duration(milliseconds: 1000),
      ),
    );
  }
}

// Professional gradient decorations
class AppGradients {
  static const LinearGradient darkBackgroundGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFF0A0F1A), Color(0xFF141A22), Color(0xFF111723)],
    stops: [0.0, 0.6, 1.0],
  );

  static const LinearGradient darkSurfaceGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [AppColors.darkSurface, AppColors.darkSurfaceVariant],
    stops: [0.0, 1.0],
  );
}

// Professional typography system using Inter
TextTheme _createProfessionalTextTheme() {
  const baseColor = AppColors.darkOnSurface;
  const secondaryColor = AppColors.darkOnSurfaceVariant;

  return const TextTheme().copyWith(
    // Display styles - for main headings
    displayLarge: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 62,
      fontWeight: FontWeight.w800,
      letterSpacing: -0.5,
      color: baseColor,
      height: 1.1,
    ),
    displayMedium: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 48,
      fontWeight: FontWeight.w800,
      letterSpacing: -0.25,
      color: baseColor,
      height: 1.15,
    ),
    displaySmall: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 40,
      fontWeight: FontWeight.w800,
      letterSpacing: 0,
      color: baseColor,
      height: 1.2,
    ),

    // Headline styles - for section titles
    headlineLarge: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 35,
      fontWeight: FontWeight.w800,
      letterSpacing: 0,
      color: baseColor,
      height: 1.25,
    ),
    headlineMedium: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 31,
      fontWeight: FontWeight.w800,
      letterSpacing: 0.15,
      color: baseColor,
      height: 1.3,
    ),
    headlineSmall: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 26,
      fontWeight: FontWeight.w800,
      letterSpacing: 0.15,
      color: baseColor,
      height: 1.35,
    ),

    // Title styles - for card titles and prominent text
    titleLarge: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 24,
      fontWeight: FontWeight.w700,
      letterSpacing: 0.1,
      color: baseColor,
      height: 1.4,
    ),
    titleMedium: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 20,
      fontWeight: FontWeight.w700,
      letterSpacing: 0.15,
      color: baseColor,
      height: 1.45,
    ),
    titleSmall: const TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 18,
      fontWeight: FontWeight.w700,
      letterSpacing: 0.15,
      color: baseColor,
      height: 1.45,
    ),

    // Body styles - for main content
    bodyLarge: const TextStyle(
      fontFamily: kFontFamily,
      fontSize: 16,
      fontWeight: FontWeight.w400,
      letterSpacing: 0.1,
      color: baseColor,
      height: 1.5,
    ),
    bodyMedium: const TextStyle(
      fontFamily: kFontFamily,
      fontSize: 14,
      fontWeight: FontWeight.w400,
      letterSpacing: 0.15,
      color: secondaryColor,
      height: 1.55,
    ),
    bodySmall: const TextStyle(
      fontFamily: kFontFamily,
      fontSize: 12,
      fontWeight: FontWeight.w400,
      letterSpacing: 0.25,
      color: secondaryColor,
      height: 1.6,
    ),

    // Label styles - for UI elements
    labelLarge: const TextStyle(
      fontFamily: kFontFamily,
      fontSize: 14,
      fontWeight: FontWeight.w500,
      letterSpacing: 0.3,
      color: baseColor,
      height: 1.4,
    ),
    labelMedium: const TextStyle(
      fontFamily: kFontFamily,
      fontSize: 12,
      fontWeight: FontWeight.w500,
      letterSpacing: 0.3,
      color: baseColor,
      height: 1.35,
    ),
    labelSmall: const TextStyle(
      fontFamily: kFontFamily,
      fontSize: 11,
      fontWeight: FontWeight.w500,
      letterSpacing: 0.3,
      color: secondaryColor,
      height: 1.3,
    ),
  );
}

// Professional Dark Theme with Gradient Backgrounds
final darkTheme = ThemeData(
  useMaterial3: true,
  brightness: Brightness.dark,
  scaffoldBackgroundColor: Colors.transparent,
  colorScheme: const ColorScheme.dark(
    primary: AppColors.darkPrimary,
    secondary: AppColors.darkSecondary,
    surface: AppColors.darkSurface,
    surfaceContainerHighest: AppColors.darkSurfaceVariant,
    onSurface: AppColors.darkOnSurface,
    onSurfaceVariant: AppColors.darkOnSurfaceVariant,
    outline: AppColors.darkOutline,
    error: Color(0xFFEF4444),
    onError: Colors.white,
  ),
  textTheme: _createProfessionalTextTheme(),

  // Professional dark card design with subtle elevation
  cardTheme: CardThemeData(
    elevation: 0,
    color: AppColors.darkSurface,
    surfaceTintColor: Colors.transparent,
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(16),
      side: BorderSide(
        color: AppColors.darkOutline.withValues(alpha: 0.3),
        width: 0.5,
      ),
    ),
    margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
  ),

  // Professional dark chip design
  chipTheme: ChipThemeData(
    backgroundColor: AppColors.darkSurfaceVariant,
    selectedColor: AppColors.darkPrimary.withValues(alpha: 0.25),
    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
    labelPadding: const EdgeInsets.symmetric(horizontal: 8),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
    labelStyle: const TextStyle(
      fontFamily: kFontFamily,
      fontWeight: FontWeight.w500,
      fontSize: 14,
      letterSpacing: 0.15,
      color: AppColors.darkOnSurface,
    ),
    elevation: 0,
    pressElevation: 0,
  ),

  // Professional dark button designs
  elevatedButtonTheme: ElevatedButtonThemeData(
    style: ElevatedButton.styleFrom(
      backgroundColor: AppColors.darkActionPrimary,
      foregroundColor: Colors.white,
      elevation: 0,
      shadowColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
      textStyle: const TextStyle(
        fontFamily: kFontFamily,
        fontWeight: FontWeight.w600,
        fontSize: 16,
        letterSpacing: 0.15,
      ),
    ),
  ),

  filledButtonTheme: FilledButtonThemeData(
    style: FilledButton.styleFrom(
      backgroundColor: AppColors.darkActionPrimary,
      foregroundColor: Colors.white,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
      textStyle: const TextStyle(
        fontFamily: kFontFamily,
        fontWeight: FontWeight.w600,
        fontSize: 16,
        letterSpacing: 0.15,
      ),
    ),
  ),

  outlinedButtonTheme: OutlinedButtonThemeData(
    style: OutlinedButton.styleFrom(
      foregroundColor: AppColors.darkPrimary,
      side: const BorderSide(color: AppColors.darkPrimary, width: 1.5),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
      textStyle: const TextStyle(
        fontFamily: kFontFamily,
        fontWeight: FontWeight.w600,
        fontSize: 16,
        letterSpacing: 0.15,
      ),
    ),
  ),

  // Professional dark input design
  inputDecorationTheme: InputDecorationTheme(
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: const BorderSide(color: AppColors.darkOutline, width: 1.5),
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: const BorderSide(color: AppColors.darkOutline, width: 1.5),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(12),
      borderSide: const BorderSide(color: AppColors.darkPrimary, width: 2),
    ),
    filled: true,
    fillColor: AppColors.darkSurfaceVariant,
    contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
    hintStyle: const TextStyle(
      fontFamily: kFontFamily,
      color: AppColors.darkOnSurfaceVariant,
      fontSize: 16,
      fontWeight: FontWeight.w400,
    ),
  ),

  // Professional dark app bar
  appBarTheme: const AppBarTheme(
    elevation: 0,
    scrolledUnderElevation: 0,
    backgroundColor: AppColors.darkBackground,
    surfaceTintColor: Colors.transparent,
    foregroundColor: AppColors.darkOnSurface,
    titleTextStyle: TextStyle(
      fontFamily: kHeadlineFontFamily,
      fontSize: 22,
      fontWeight: FontWeight.w800,
      color: AppColors.darkOnSurface,
      letterSpacing: 0.1,
    ),
    systemOverlayStyle: SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
    ),
  ),

  // Professional navigation theming
  bottomNavigationBarTheme: const BottomNavigationBarThemeData(
    backgroundColor: AppColors.darkSurface,
    selectedItemColor: AppColors.darkPrimary,
    unselectedItemColor: AppColors.darkOnSurfaceVariant,
    elevation: 0,
    type: BottomNavigationBarType.fixed,
  ),

  navigationRailTheme: const NavigationRailThemeData(
    backgroundColor: AppColors.darkSurface,
    selectedIconTheme: IconThemeData(color: AppColors.darkPrimary),
    unselectedIconTheme: IconThemeData(color: AppColors.darkOnSurfaceVariant),
    selectedLabelTextStyle: TextStyle(color: AppColors.darkPrimary),
    unselectedLabelTextStyle: TextStyle(color: AppColors.darkOnSurfaceVariant),
  ),

  // Dark blue progress indicators
  progressIndicatorTheme: const ProgressIndicatorThemeData(
    color: AppColors.darkPrimary,
    linearTrackColor: AppColors.darkSurfaceVariant,
    circularTrackColor: AppColors.darkSurfaceVariant,
  ),
);
