plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.zapstore.app"
    compileSdk = providers.gradleProperty("purplequartz.compileSdk").orElse("37").get().toInt()

    defaultConfig {
        applicationId = "dev.zapstore.beta"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":purplequartz"))
    implementation(libs.coroutines.android)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
}
