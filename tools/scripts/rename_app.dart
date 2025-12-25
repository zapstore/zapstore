// ignore_for_file: avoid_print

import 'dart:io';
import 'package:args/args.dart';

const String kOriginalAppId = 'com.example.purplestack';
const String kOriginalAppName = 'Purplestack';
const String kOriginalAppNameSnakeCase = 'purplestack';

/// Converts a string to PascalCase (removes spaces and capitalizes each word)
String _toPascalCase(String input) {
  return input
      .split(RegExp(r'[\s_-]+')) // Split on spaces, underscores, and hyphens
      .where((word) => word.isNotEmpty)
      .map((word) => word[0].toUpperCase() + word.substring(1).toLowerCase())
      .join('');
}

void main(List<String> args) async {
  final parser = ArgParser()
    ..addOption('name', abbr: 'n', help: 'App display name')
    ..addOption(
      'app-id',
      abbr: 'i',
      help: 'App ID in reverse domain notation (e.g., com.company.app)',
    )
    ..addOption(
      'description',
      abbr: 'd',
      help: 'App description for pubspec.yaml',
    )
    ..addOption(
      'version',
      abbr: 'v',
      help: 'App version for pubspec.yaml (default: 0.1.0)',
    )
    ..addOption('icon', help: 'Path to main app icon image')
    ..addOption(
      'adaptive-background',
      help: 'Path to adaptive background image (Android)',
    )
    ..addOption(
      'adaptive-foreground',
      help: 'Path to adaptive foreground image (Android)',
    )
    ..addOption(
      'adaptive-monochrome',
      help: 'Path to adaptive monochrome image (Android)',
    )
    ..addOption(
      'notification-icon',
      help: 'Path to notification icon image (Android)',
    )
    ..addFlag(
      'help',
      abbr: 'h',
      help: 'Show usage information',
      negatable: false,
    );

  late ArgResults results;
  try {
    results = parser.parse(args);
  } catch (e) {
    print('Error: $e\n');
    _printUsage(parser);
    exit(1);
  }

  if (results['help'] as bool) {
    _printUsage(parser);
    exit(0);
  }

  // Check for mandatory parameters
  if (results['name'] == null) {
    print('Error: --name is required\n');
    _printUsage(parser);
    exit(1);
  }

  if (results['app-id'] == null) {
    print('Error: --app-id is required\n');
    _printUsage(parser);
    exit(1);
  }

  final appName = results['name'] as String;
  final appId = results['app-id'] as String;
  final appDescription = results['description'] as String?;
  final appVersion = results['version'] as String? ?? '0.1.0';

  // Create PascalCase version for Dart code (removes spaces, capitalizes each word)
  final appNamePascalCase = _toPascalCase(appName);
  final iconPath = results['icon'] as String?;
  final adaptiveBackground = results['adaptive-background'] as String?;
  final adaptiveForeground = results['adaptive-foreground'] as String?;
  final adaptiveMonochrome = results['adaptive-monochrome'] as String?;
  final notificationIcon = results['notification-icon'] as String?;

  // Validate app ID format
  if (!RegExp(r'^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$').hasMatch(appId)) {
    print(
      'Error: App ID must be in reverse domain notation (e.g., com.company.app)',
    );
    print(
      'Use only lowercase letters, numbers, and dots. Each segment must start with a letter.',
    );
    exit(1);
  }

  // Validate version format
  if (!RegExp(r'^\d+\.\d+\.\d+(\+\d+)?$').hasMatch(appVersion)) {
    print(
      'Error: Version must be in format x.y.z or x.y.z+build (e.g., 1.0.0 or 1.0.0+1)',
    );
    exit(1);
  }

  // Validate icon paths exist if provided
  final iconPaths = [
    iconPath,
    adaptiveBackground,
    adaptiveForeground,
    adaptiveMonochrome,
    notificationIcon,
  ].where((path) => path != null).cast<String>();

  for (final iconPathToCheck in iconPaths) {
    if (!File(iconPathToCheck).existsSync()) {
      print('Error: Icon file not found: $iconPathToCheck');
      exit(1);
    }
  }

  final appNameSnakeCase = appName.toLowerCase().replaceAll(
    RegExp(r'[^a-z0-9]'),
    '_',
  );

  print('Renaming Electric app...');
  print('Original App ID: $kOriginalAppId → $appId');
  print('Original App Name: $kOriginalAppName → $appName');
  print('App Name (PascalCase): $appNamePascalCase');
  print('Original Snake Case: $kOriginalAppNameSnakeCase → $appNameSnakeCase');
  if (appDescription != null) print('Description: $appDescription');
  print('Version: $appVersion');
  if (iconPath != null) print('Main Icon: $iconPath');
  if (adaptiveBackground != null) {
    print('Adaptive Background: $adaptiveBackground');
  }
  if (adaptiveForeground != null) {
    print('Adaptive Foreground: $adaptiveForeground');
  }
  if (adaptiveMonochrome != null) {
    print('Adaptive Monochrome: $adaptiveMonochrome');
  }
  if (notificationIcon != null) print('Notification Icon: $notificationIcon');
  print('');

  final renamer = AppRenamer(
    appName,
    appNamePascalCase,
    appId,
    appNameSnakeCase,
    appDescription,
    appVersion,
    iconPath: iconPath,
    adaptiveBackground: adaptiveBackground,
    adaptiveForeground: adaptiveForeground,
    adaptiveMonochrome: adaptiveMonochrome,
    notificationIcon: notificationIcon,
  );

  try {
    await renamer.renameApp();

    // Clean and get dependencies
    await _cleanAndGetDependencies();

    // Generate icons if any icon paths were provided
    if (renamer._hasIconPaths()) {
      await renamer._generateIcons();
    }

    print('\n✅ Electric app renamed successfully!');
    print('\nNext steps:');
    print('1. Test the app on your target platforms');
    print('2. Commit your changes to version control');
  } catch (e) {
    print('\n❌ Error renaming app: $e');
    exit(1);
  }
}

void _printUsage(ArgParser parser) {
  print(
    'Electric App Renamer - Rename your Electric app across all platforms\n',
  );
  print(
    'Usage: dart rename_app.dart --name "App Name" --app-id "com.company.app" [options]\n',
  );
  print(
    'This tool will search all files in android/, ios/, lib/, linux/, macos/, test/, windows/ and update pubspec.yaml',
  );
  print('(excludes tools/ directory)');
  print('and replace:');
  print('- "$kOriginalAppId" with your new app ID (FIRST)');
  print(
    '- "$kOriginalAppName" with your new app name in PascalCase (for code)',
  );
  print('- "$kOriginalAppNameSnakeCase" with your new app name in snake_case');
  print('- "com.example" with your new app name (LAST)');
  print('- pubspec.yaml name, description, and version fields specifically');
  print('- version defaults to 0.1.0 if not provided\n');
  print('Options:');
  print(parser.usage);
  print('\nExamples:');
  print('  # Basic rename (spaces in name are allowed)');
  print(
    '  dart rename_app.dart --name "My Super App" --app-id "com.mycompany.myapp"',
  );
  print('');
  print('  # With description and version');
  print(
    '  dart rename_app.dart --name "My App" --app-id "com.mycompany.myapp" --description "A Flutter app for managing tasks" --version "1.0.0"',
  );
  print('');
  print('  # With main icon');
  print(
    '  dart rename_app.dart --name "My App" --app-id "com.mycompany.myapp" --icon "assets/icon.png"',
  );
  print('');
  print('  # With Android adaptive icons');
  print(
    '  dart rename_app.dart --name "My App" --app-id "com.mycompany.myapp" \\',
  );
  print('    --icon "assets/icon.png" \\');
  print('    --adaptive-background "assets/adaptive-bg.png" \\');
  print('    --adaptive-foreground "assets/adaptive-fg.png" \\');
  print('    --adaptive-monochrome "assets/adaptive-mono.png"');
}

class AppRenamer {
  final String appName;
  final String appNamePascalCase;
  final String appId;
  final String appNameSnakeCase;
  final String? appDescription;
  final String appVersion;
  final String? iconPath;
  final String? adaptiveBackground;
  final String? adaptiveForeground;
  final String? adaptiveMonochrome;
  final String? notificationIcon;

  AppRenamer(
    this.appName,
    this.appNamePascalCase,
    this.appId,
    this.appNameSnakeCase,
    this.appDescription,
    this.appVersion, {
    this.iconPath,
    this.adaptiveBackground,
    this.adaptiveForeground,
    this.adaptiveMonochrome,
    this.notificationIcon,
  });

  Future<void> renameApp() async {
    print('🔍 Searching and replacing in all files...');

    // Directories to search (excluding tools)
    final searchDirs = [
      'android',
      'ios',
      'lib',
      'linux',
      'macos',
      'test',
      'windows',
    ];

    int filesProcessed = 0;
    int filesChanged = 0;

    // Process pubspec.yaml specifically
    final pubspecFile = File('pubspec.yaml');
    if (pubspecFile.existsSync()) {
      final result = await _processPubspecFile(pubspecFile);
      filesProcessed++;
      if (result) filesChanged++;
    }

    // Process all files in search directories
    for (final dirName in searchDirs) {
      final dir = Directory(dirName);
      if (!dir.existsSync()) {
        print('⚠️  Directory $dirName not found, skipping');
        continue;
      }

      await for (final entity in dir.list(recursive: true)) {
        if (entity is File) {
          final result = await _processFile(entity);
          filesProcessed++;
          if (result) filesChanged++;
        }
      }
    }

    print('✅ Processed $filesProcessed files, changed $filesChanged files');
  }

  Future<bool> _processPubspecFile(File file) async {
    try {
      String content = await file.readAsString();
      String originalContent = content;

      // Replace the exact snake_case project name
      content = content.replaceAll(
        RegExp('^name:\\s+$kOriginalAppNameSnakeCase\$', multiLine: true),
        'name: $appNameSnakeCase',
      );

      // Replace description if provided
      if (appDescription != null) {
        content = content.replaceAll(
          RegExp(r'^description:.*$', multiLine: true),
          'description: $appDescription',
        );
      }

      // Replace version (always overwrite with default 0.1.0 or provided value)
      content = content.replaceAll(
        RegExp(r'^version:\s+[\d\.]+(\+\d+)?$', multiLine: true),
        'version: $appVersion',
      );

      // Write back if content changed
      if (content != originalContent) {
        await file.writeAsString(content);
        print('  ✓ Updated ${file.path}');
        return true;
      }

      return false;
    } catch (e) {
      print('  ⚠️  Error updating ${file.path}: $e');
      return false;
    }
  }

  Future<bool> _processFile(File file) async {
    try {
      String content = await file.readAsString();
      String originalContent = content;

      // Perform exact string replacements - APP ID FIRST!
      content = content.replaceAll(kOriginalAppId, appId);

      // Use PascalCase for Dart code identifiers (no spaces)
      content = content.replaceAll(kOriginalAppName, appNamePascalCase);
      content = content.replaceAll(kOriginalAppNameSnakeCase, appNameSnakeCase);

      // For display names in platform files, we might want the full name with spaces
      // Check if this is a platform-specific file that needs display names
      if (_shouldUseDisplayName(file.path)) {
        // In platform files, replace PascalCase back with display name for labels
        content = content.replaceAll(appNamePascalCase, appName);
      }

      content = content.replaceAll('com.example', appName);

      // Write back if content changed
      if (content != originalContent) {
        await file.writeAsString(content);
        print('  ✓ Updated ${file.path}');
        return true;
      }

      return false;
    } catch (e) {
      // Skip binary files or files we can't read/write
      return false;
    }
  }

  /// Determines if a file should use the display name (with spaces) instead of PascalCase
  /// Platform files like AndroidManifest.xml and Info.plist need display names for labels
  bool _shouldUseDisplayName(String filePath) {
    return filePath.contains('AndroidManifest.xml') ||
        filePath.contains('Info.plist') ||
        filePath.contains('strings.xml') ||
        filePath.contains('.xml') && filePath.contains('android') ||
        filePath.endsWith('.plist');
  }

  bool _hasIconPaths() {
    return iconPath != null ||
        adaptiveBackground != null ||
        adaptiveForeground != null ||
        adaptiveMonochrome != null ||
        notificationIcon != null;
  }

  Future<void> _generateIcons() async {
    print('🎨 Generating app icons...');

    final pubspecFile = File('pubspec.yaml');
    if (!pubspecFile.existsSync()) {
      throw Exception('pubspec.yaml not found');
    }

    String originalContent = await pubspecFile.readAsString();
    String modifiedContent = originalContent;

    try {
      // Add icons_launcher configuration to pubspec.yaml
      final iconsConfig = _buildIconsLauncherConfig();
      modifiedContent = '$originalContent\n$iconsConfig';
      await pubspecFile.writeAsString(modifiedContent);

      // Run icons_launcher
      final result = await Process.run('dart', [
        'run',
        'icons_launcher:create',
      ]);

      if (result.exitCode == 0) {
        print('✅ Icons generated successfully');
        if (result.stdout.toString().isNotEmpty) {
          print('Icons output: ${result.stdout}');
        }
      } else {
        print('❌ Failed to generate icons');
        print('Error: ${result.stderr}');
        throw Exception('Icon generation failed: ${result.stderr}');
      }
    } finally {
      // Restore original pubspec.yaml
      await pubspecFile.writeAsString(originalContent);
    }
  }

  String _buildIconsLauncherConfig() {
    final buffer = StringBuffer();
    buffer.writeln('icons_launcher:');

    // Main icon path (required by icons_launcher)
    final mainIcon = iconPath ?? adaptiveForeground ?? adaptiveBackground;
    if (mainIcon != null) {
      buffer.writeln('  image_path: "$mainIcon"');
    }

    buffer.writeln('  platforms:');

    // Android configuration
    buffer.writeln('    android:');
    buffer.writeln('      enable: true');
    if (iconPath != null) {
      buffer.writeln('      image_path: "$iconPath"');
    }
    if (notificationIcon != null) {
      buffer.writeln('      notification_image: "$notificationIcon"');
    } else if (iconPath != null) {
      buffer.writeln('      notification_image: "$iconPath"');
    }
    if (adaptiveBackground != null) {
      buffer.writeln('      adaptive_background_image: "$adaptiveBackground"');
    }
    if (adaptiveForeground != null) {
      buffer.writeln('      adaptive_foreground_image: "$adaptiveForeground"');
    }
    if (adaptiveMonochrome != null) {
      buffer.writeln('      adaptive_monochrome_image: "$adaptiveMonochrome"');
    }

    // iOS configuration
    buffer.writeln('    ios:');
    buffer.writeln('      enable: true');
    if (iconPath != null) {
      buffer.writeln('      image_path: "$iconPath"');
    }

    // macOS configuration
    buffer.writeln('    macos:');
    buffer.writeln('      enable: true');
    if (iconPath != null) {
      buffer.writeln('      image_path: "$iconPath"');
    }

    // Windows configuration
    buffer.writeln('    windows:');
    buffer.writeln('      enable: true');
    if (iconPath != null) {
      buffer.writeln('      image_path: "$iconPath"');
    }

    // Linux configuration
    buffer.writeln('    linux:');
    buffer.writeln('      enable: true');
    if (iconPath != null) {
      buffer.writeln('      image_path: "$iconPath"');
    }

    return buffer.toString();
  }
}

Future<void> _cleanAndGetDependencies() async {
  print('\n🧹 Cleaning pub cache and getting dependencies...');

  try {
    // Check if we have flutter command available
    print('Running flutter clean...');
    final cleanResult = await Process.run('flutter', ['clean']);
    if (cleanResult.exitCode != 0) {
      print('⚠️  Flutter clean failed, but continuing...');
    }

    print('Running dart pub cache clean...');
    final cacheResult = await Process.run('dart', ['pub', 'cache', 'clean']);
    if (cacheResult.exitCode != 0) {
      print('⚠️  Pub cache clean failed, but continuing...');
    }

    print('Running flutter pub get...');
    final pubGetResult = await Process.run('flutter', ['pub', 'get']);
    if (pubGetResult.exitCode != 0) {
      print('❌ Flutter pub get failed:');
      print(pubGetResult.stderr);
      throw Exception('Failed to run flutter pub get');
    }

    print('✓ Dependencies updated successfully');
  } catch (e) {
    print('⚠️  Error cleaning dependencies: $e');
    print('Please run the following commands manually:');
    print('  flutter clean');
    print('  dart pub cache clean');
    print('  flutter pub get');
  }
}
