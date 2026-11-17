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

val buildsRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

// The catalog bundle is what a new install imports on first open. -PcatalogBundle chooses it:
//   none (default)  no bundle; the first open downloads the catalog. This is the update APK.
//   pinned          the bundle named by catalogPinUrl and catalogPinSha256 in gradle.properties.
//   <path>          a local file, for debug builds against a local relay or the e2e tests.
// -PcatalogBundleUrl fetches the pinned bytes from another URL, or a file: URL; the sha256 still has to match.
val catalogBundleAsset = "bundle-0-1.tar.zst"
val catalogPinSha256 = providers.gradleProperty("catalogPinSha256").orElse("")
val catalogPinUrl = providers.gradleProperty("catalogPinUrl").orElse("")
val catalogBundleStage = layout.projectDirectory.dir(".tools/catalog-bundle")
val catalogBundleMode = providers.gradleProperty("catalogBundle").orElse("none").get().trim().ifEmpty { "none" }
val catalogBundleUrl = providers.gradleProperty("catalogBundleUrl").orElse("")
check(!buildsRelease || catalogBundleMode == "none" || catalogBundleMode == "pinned") {
    "a release build ships no catalog bundle or the pinned one: -PcatalogBundle=none or pinned, not a file"
}
// A seed left in src/main/assets would ride along in every build, release included.
check(!layout.projectDirectory.file("src/main/assets/$catalogBundleAsset").asFile.exists()) {
    "src/main/assets/$catalogBundleAsset is a leftover seed; delete it and use -PcatalogBundle=<file>"
}

android {
    namespace = "dev.zapstore.app"
    compileSdk = providers.gradleProperty("iolite.compileSdk").orElse("37").get().toInt()

    defaultConfig {
        applicationId = "dev.zapstore.app"
        minSdk = 29
        targetSdk = 37
        // Above every Flutter 1.1.3 split (armeabi-v7a 2010, arm64-v8a 3010, x86_64 5010).
        versionCode = 6004
        versionName = "2.0.4"
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
            // Zapstore is sideloaded, so there is no Play delivery to shrink the download. Stored libraries
            // are mapped from the APK and cost 18 MB of download; compressed ones are extracted at install.
            useLegacyPackaging = true
        }
        dex {
            useLegacyPackaging = true
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

    // `make run` passes -PCATALOG_RELAY. The daemon ignores a new environment, so the property is the input.
    val catalogRelay = providers.gradleProperty("CATALOG_RELAY").orElse("wss://relay.zapstore.dev").get().trim()
        .ifEmpty { "wss://relay.zapstore.dev" }
    val catalogRelayField = "\"" + catalogRelay.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // The default catalog must be signed by this key. A local relay signs with its own: pass
    // -PCATALOG_PUBKEY=<hex>, or an empty value in a debug build to trust the first bundle's signer.
    val productionCatalogRelay = "wss://relay.zapstore.dev"
    val productionCatalogPubkey = "44dc0ab0606436c14454d7ecf3581b7b4f1b48857fd9851896a07dcf2b72b2bb"
    val catalogPubkey = providers.gradleProperty("CATALOG_PUBKEY")
        .orElse(if (catalogRelay.trimEnd('/') == productionCatalogRelay) productionCatalogPubkey else "")
        .get().trim().lowercase()
    check(catalogPubkey.isEmpty() || Regex("[0-9a-f]{64}").matches(catalogPubkey)) {
        "CATALOG_PUBKEY must be 64 hex characters"
    }
    check(!buildsRelease || catalogPubkey.isNotEmpty()) {
        "a release build needs CATALOG_PUBKEY for $catalogRelay"
    }
    val catalogPubkeyField = "\"$catalogPubkey\""

    // ~/.android is not readable in this environment. AGP only stats that
    // directory when the debug config still uses alias AndroidDebugKey.
    signingConfigs {
        getByName("debug") {
            storeFile = layout.projectDirectory.file("debug.keystore").asFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Catalog sync uses CATALOG_RELAY.
        debug {
            buildConfigField("String", "CATALOG_RELAY", catalogRelayField)
            buildConfigField("String", "CATALOG_PUBKEY", catalogPubkeyField)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "CATALOG_RELAY", catalogRelayField)
            buildConfigField("String", "CATALOG_PUBKEY", catalogPubkeyField)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.named("main") {
        assets.directories.add(layout.projectDirectory.dir(".tools/leaf-ir-assets").asFile.absolutePath)
        assets.directories.add(catalogBundleStage.asFile.absolutePath)
    }
}

kotlin {
    jvmToolchain(21)
}

// Install the debug APK, then restart the app. `make watch` runs this under `gradlew -t`,
// so every saved change rebuilds, reinstalls and relaunches without clearing app data.
tasks.register<Exec>("runDebug") {
    dependsOn("installDebug")
    val adb = layout.projectDirectory.file("tools/adb").asFile.absolutePath
    commandLine(adb, "shell", "am", "start", "-S", "-W", "-n", "dev.zapstore.app/dev.zapstore.app.MainActivity")
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

val ensureDebugKeystore = tasks.register<Exec>("ensureDebugKeystore") {
    val store = layout.projectDirectory.file("debug.keystore").asFile
    val keytool = File(System.getProperty("java.home"), "bin/keytool")
    outputs.file(store)
    onlyIf { !store.isFile }
    executable = keytool.absolutePath
    args(
        "-genkeypair",
        "-keystore", store.absolutePath,
        "-storepass", "android",
        "-keypass", "android",
        "-alias", "androiddebugkey",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Android Debug,O=Android,C=US",
    )
}

tasks.matching { it.name.startsWith("validateSigning") }.configureEach {
    dependsOn(ensureDebugKeystore)
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
        ).forEach { (name, url, sum) -> fetchVerified(url, sum, root.resolve(name)) }
    }
}

// Puts the catalog bundle for -PcatalogBundle into .tools/catalog-bundle, an asset directory.
// The directory is emptied first, so switching modes never leaves a bundle behind.
val stageCatalogBundle = tasks.register("stageCatalogBundle") {
    inputs.property("mode", catalogBundleMode)
    inputs.property("url", catalogBundleUrl)
    when (catalogBundleMode) {
        "none" -> Unit
        "pinned" -> {
            inputs.property("pinSha256", catalogPinSha256)
            inputs.property("pinUrl", catalogPinUrl)
        }
        else -> inputs.files(File(catalogBundleMode))
    }
    outputs.dir(catalogBundleStage)
    doLast {
        val stage = catalogBundleStage.asFile
        stage.deleteRecursively()
        stage.mkdirs()
        val target = stage.resolve(catalogBundleAsset)
        when (catalogBundleMode) {
            "none" -> Unit
            "pinned" -> {
                val sha = catalogPinSha256.get()
                check(sha.isNotEmpty()) { "gradle.properties has no catalogPinSha256; infra/misc/cut-mobile-release.sh writes it" }
                val url = catalogBundleUrl.get().ifEmpty { catalogPinUrl.get() }
                check(url.isNotEmpty()) { "gradle.properties has no catalogPinUrl; infra/misc/cut-mobile-release.sh writes it" }
                val cached = layout.projectDirectory.file(".tools/catalog-cache/$sha.tar.zst").asFile
                fetchVerified(url, sha, cached)
                cached.copyTo(target, overwrite = true)
            }
            else -> File(catalogBundleMode).also { check(it.isFile) { "catalogBundle file not found: $it" } }
                .copyTo(target, overwrite = true)
        }
    }
}

tasks.matching { it.name == "preBuild" || it.name.endsWith("UnitTest") || (it.name.startsWith("merge") && it.name.endsWith("Assets")) }.configureEach {
    dependsOn(downloadLeafModel, stageCatalogBundle)
}

configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

/** Fetches [url] (https or file) to [dest] unless [dest] already has [sha256]. Bytes that do not match are deleted and fail the build. */
fun fetchVerified(url: String, sha256: String, dest: File) {
    if (dest.isFile && sha256(dest) == sha256) return
    dest.parentFile.mkdirs()
    val tmp = File(dest.path + ".tmp")
    val connection = URI(url).toURL().openConnection()
    (connection as? HttpURLConnection)?.instanceFollowRedirects = true
    connection.setRequestProperty("User-Agent", "zapstore-android")
    connection.connectTimeout = 30_000
    connection.readTimeout = 180_000
    connection.getInputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
    val actual = sha256(tmp)
    if (actual != sha256) {
        tmp.delete()
        error("${dest.name} from $url has sha256 $actual, expected $sha256")
    }
    check(tmp.renameTo(dest)) { "could not replace ${dest.name}" }
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
    implementation(libs.apksig)
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
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasklist)
    implementation(libs.commonmark.autolink)
    implementation(libs.okhttp)
    implementation(libs.security.crypto)
    implementation(libs.work.runtime)

    testImplementation("org.json:json:20250517")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.navigation.testing)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
