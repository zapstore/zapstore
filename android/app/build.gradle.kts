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
    // - With ZAPSTORE_KEY_PATH + ZAPSTORE_KEY_PASSWORD env vars -> signed
    // - Without env vars -> unsigned (for reproducible builds / F-Droid)
    val envKeyPassword = System.getenv("ZAPSTORE_KEY_PASSWORD")?.trim()
    val envKeyPath = System.getenv("ZAPSTORE_KEY_PATH")?.trim()

    val envKeyAlias = System.getenv("ZAPSTORE_KEY_ALIAS")?.trim()

    val releaseSigningConfig = if (envKeyPassword != null && envKeyPath != null && envKeyAlias != null) {
        val store = file(envKeyPath)
        if (!store.exists()) {
            error("ZAPSTORE_KEY_PATH points to a file that does not exist: $envKeyPath")
        }
        signingConfigs.create("release") {
            storeFile = store
            storePassword = envKeyPassword
            keyAlias = envKeyAlias
            keyPassword = envKeyPassword
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
