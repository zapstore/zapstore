import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Load signing properties from key.properties (not checked into VCS)
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "dev.zapstore.app"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "dev.zapstore.app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 29
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // Release signing is optional:
    // - With a complete key.properties -> signed (for Play/GitHub releases)
    // - Without key.properties (or incomplete) -> unsigned (for reproducible builds / F-Droid)
    val releaseSigningConfig = if (keystorePropertiesFile.exists()) {
        val alias = keystoreProperties.getProperty("keyAlias")?.trim().orEmpty()
        val keyPass = keystoreProperties.getProperty("keyPassword")?.trim().orEmpty()
        val storePath = keystoreProperties.getProperty("storeFile")?.trim().orEmpty()
        val storePass = keystoreProperties.getProperty("storePassword")?.trim().orEmpty()

        val store = storePath.takeIf { it.isNotBlank() }?.let { file(it) }
        val isComplete = alias.isNotBlank() && keyPass.isNotBlank() && store != null && store.exists() && storePass.isNotBlank()

        if (isComplete) {
            signingConfigs.create("release") {
                storeFile = store
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        } else {
            logger.warn("key.properties found but incomplete. Building unsigned release APK.")
            null
        }
    } else {
        null
    }

    buildTypes {
        release {
            // Signed only when key.properties is present AND complete.
            signingConfig = releaseSigningConfig
            // Enable code shrinking and resource optimization
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles (for Google Play)
        includeInBundle = false
    }

    packagingOptions {
        jniLibs {
            // Compress native .so files inside the APK to reduce artifact size
            useLegacyPackaging = true
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
}
