import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.zapstore.app"
    compileSdk = providers.gradleProperty("iolite.compileSdk").orElse("37").get().toInt()

    defaultConfig {
        applicationId = "dev.zapstore.beta"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // The release profile in tools/arti-build already strips libarti_android.so.
    // AGP's extra llvm-strip only rewrites .comment and would break a
    // reproducible match against the committed jniLibs copy.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libarti_android.so"
        }
        resources {
            excludes += setOf(
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    androidResources {
        localeFilters += "en"
    }

    buildTypes {
        // Debug builds never open relay sockets. Catalog sync uses CATALOG_RELAY.
        debug {
            buildConfigField("boolean", "RELAYS_ENABLED", "false")
            buildConfigField("String", "CATALOG_RELAY", "\"wss://brelay.zapstore.dev\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("boolean", "RELAYS_ENABLED", "true")
            buildConfigField("String", "CATALOG_RELAY", "\"wss://brelay.zapstore.dev\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.named("main") {
        assets.directories.add(layout.projectDirectory.dir(".tools/leaf-ir-assets").asFile.absolutePath)
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.register("verifyArtiAbis") {
    val jniLibs = layout.projectDirectory.dir("src/main/jniLibs")
    val abis = listOf("arm64-v8a")
    doLast {
        abis.forEach { abi ->
            val so = jniLibs.file("$abi/libarti_android.so").asFile
            check(so.isFile) {
                "Missing ${so.path} — run make vendor"
            }
        }
    }
}

tasks.matching { it.name.startsWith("assembleRelease") }.configureEach {
    dependsOn("verifyArtiAbis")
}

val downloadLeafModel = tasks.register("downloadLeafModel") {
    val dest = layout.projectDirectory.dir(".tools/leaf-ir-assets/leaf-ir-v1")
    inputs.property(
        "checksums",
        listOf(
            "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
            "b3e7c0e1ef65e39a5ef1ca3bc5e4aef5feafb6e204bd161503145ed062f12c69",
            "8c08cb4ecc00bad721b117a85738c537ceb9b85b3eedc52d9c6906fddaa55718",
            "e77ea96a124230e23e5bac8e26c3ffe5410154973d8635673fa0220b06c13f8b",
        ),
    )
    outputs.dir(dest)
    doLast {
        val root = dest.asFile
        root.mkdirs()
        val rev = "4262131b32c3182bd06e67e92ae69d7bd66e0c5c"
        val base = "https://huggingface.co/MongoDB/mdbr-leaf-ir/resolve/$rev/"
        listOf(
            Triple("vocab.txt", base + "vocab.txt", "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"),
            Triple("dense.safetensors", base + "2_Dense/model.safetensors", "b3e7c0e1ef65e39a5ef1ca3bc5e4aef5feafb6e204bd161503145ed062f12c69"),
            Triple("model_quantized.onnx", base + "onnx/model_quantized.onnx", "8c08cb4ecc00bad721b117a85738c537ceb9b85b3eedc52d9c6906fddaa55718"),
            Triple("model_quantized.onnx_data", base + "onnx/model_quantized.onnx_data", "e77ea96a124230e23e5bac8e26c3ffe5410154973d8635673fa0220b06c13f8b"),
        ).forEach { (name, url, sum) ->
            val file = root.resolve(name)
            if (file.isFile && sha256(file) == sum) return@forEach
            val tmp = root.resolve("$name.tmp")
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "zapstore-android")
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            check(sha256(tmp) == sum) { "$name sha256 mismatch" }
            check(tmp.renameTo(file)) { "could not replace $name" }
        }
    }
}

tasks.matching { it.name == "preBuild" || it.name.endsWith("UnitTest") || (it.name.startsWith("merge") && it.name.endsWith("Assets")) }.configureEach {
    dependsOn(downloadLeafModel)
}

configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

dependencies {
    implementation(project(":iolite"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.sqlite)
    implementation(libs.sqlite.bundled)
    implementation(libs.onnxruntime.android)

    testImplementation("org.json:json:20250517")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.sqlite.bundled)
    testImplementation(libs.onnxruntime)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.navigation.testing)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
