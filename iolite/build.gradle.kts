import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.zapstore.iolite"
    compileSdk = providers.gradleProperty("iolite.compileSdk").orElse("37").get().toInt()

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.secp256k1)
    implementation(libs.secp256k1.jni.android)
    implementation(libs.coroutines.android)
    implementation(libs.sqlite)
    implementation(libs.sqlite.bundled)
    implementation(libs.onnxruntime.android)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")

    testImplementation("org.json:json:20250517")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.secp256k1.jni.jvm)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.onnxruntime)
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.test)
}

configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(rootProject.tasks.named("downloadLeafModel"))
}
