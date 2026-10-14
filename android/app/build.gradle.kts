import org.gradle.api.GradleException
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

fun runGit(vararg args: String): String? = try {
    val process = ProcessBuilder("git", *args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() == 0 && output.isNotEmpty()) output else null
} catch (_: Exception) {
    null
}

private fun versionCodeFromTag(tag: String?): Int? {
    val match = Regex("^v?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(tag.orEmpty()) ?: return null
    val major = match.groupValues[1].toLongOrNull() ?: return null
    val minor = match.groupValues[2].toLongOrNull() ?: 0L
    val patch = match.groupValues[3].toLongOrNull() ?: 0L
    if (major > Int.MAX_VALUE.toLong() / 1_000_000L || minor !in 0..999L || patch !in 0..999L) {
        return null
    }
    val code = major * 1_000_000L + minor * 1_000L + patch
    return code.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}

fun gitVersionCode(): Int {
    val tag = runGit("describe", "--tags", "--abbrev=0")
    val base = versionCodeFromTag(tag)
    val commits = runGit(
        "rev-list",
        "--count",
        if (tag == null) "HEAD" else "$tag..HEAD",
    )?.toLongOrNull()
    if (base != null) {
        return (base + (commits ?: 0L)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return (commits ?: 1L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

val releasePropsFile = rootProject.file("keystore.properties")

gradle.taskGraph.whenReady {
    val releaseArtifactRequested = allTasks.any {
        it.project == project && it.name == "packageRelease"
    }
    if (releaseArtifactRequested && !releasePropsFile.exists()) {
        throw GradleException(
            "Release signing key is missing; create android/keystore.properties before packaging"
        )
    }
}

// ---------------------------------------------------------------------------
// Native Node.js (nodejs-mobile)
//
// nodejs-mobile ships real Node 18 as `libnode.so` but no executable. Android
// only lets an app exec `lib*.so` files from its extracted native library dir,
// so we build a tiny launcher on top of libnode and package it as
// `libnoderunner.so`. The Kotlin NodeRuntime then execs it per script.
//
// Everything is cached under android/.nodejs-mobile so CI can cache the 57 MB
// download and repeated builds are offline.
// ---------------------------------------------------------------------------

val nodeMobileVersion = "18.20.4"
val nodeMobileAbis = listOf("arm64-v8a", "x86_64")
val nodeMobileDir: File = rootProject.file(".nodejs-mobile")
val nodeMobileZip = File(nodeMobileDir, "nodejs-mobile-$nodeMobileVersion-android.zip")
val nodeMobileJniLibs = File(nodeMobileDir, "jniLibs")
val nodeMobileMarker = File(nodeMobileDir, ".unpacked-$nodeMobileVersion")

val fetchNodeMobile by tasks.registering {
    description = "Downloads and unpacks nodejs-mobile (real Node $nodeMobileVersion)."
    inputs.property("version", nodeMobileVersion)
    outputs.file(nodeMobileMarker)
    doLast {
        nodeMobileDir.mkdirs()
        if (!nodeMobileZip.isFile) {
            val url = "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/" +
                "v$nodeMobileVersion/nodejs-mobile-v$nodeMobileVersion-android.zip"
            logger.lifecycle("PocketRun: downloading real Node.js from $url")
            val tmp = File(nodeMobileDir, "download.part")
            URI(url).toURL().openStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            tmp.renameTo(nodeMobileZip)
        }
        if (!nodeMobileMarker.isFile) {
            File(nodeMobileDir, "include").deleteRecursively()
            File(nodeMobileDir, "bin").deleteRecursively()
            ZipInputStream(nodeMobileZip.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val wanted = name.startsWith("include/node/") ||
                        nodeMobileAbis.any { name == "bin/$it/libnode.so" }
                    if (wanted && !entry.isDirectory) {
                        val target = File(nodeMobileDir, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zin.copyTo(out) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            nodeMobileMarker.writeText(nodeMobileVersion)
        }
    }
}

val assembleNodeJniLibs by tasks.registering {
    description = "Builds libnoderunner.so per ABI and stages libnode.so/libc++_shared.so."
    dependsOn(fetchNodeMobile)
    inputs.property("version", nodeMobileVersion)
    inputs.file(layout.projectDirectory.file("src/main/cpp/noderunner.cpp"))
    outputs.dir(nodeMobileJniLibs)
    doLast {
        val ndk = android.ndkDirectory
        val toolchain = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64")
        val sysroot = File(toolchain, "sysroot")
        val abiClang = mapOf(
            "arm64-v8a" to ("aarch64-linux-android26-clang++" to "aarch64-linux-android"),
            "x86_64" to ("x86_64-linux-android26-clang++" to "x86_64-linux-android"),
        )
        nodeMobileJniLibs.deleteRecursively()
        for (abi in nodeMobileAbis) {
            val (clangName, stlDir) = abiClang.getValue(abi)
            val dest = File(nodeMobileJniLibs, abi).apply { mkdirs() }

            val libnode = File(nodeMobileDir, "bin/$abi/libnode.so")
            require(libnode.isFile) { "nodejs-mobile: missing $libnode (run fetchNodeMobile)" }
            libnode.copyTo(File(dest, "libnode.so"), overwrite = true)

            val stl = File(sysroot, "usr/lib/$stlDir/libc++_shared.so")
            require(stl.isFile) { "NDK: missing $stl" }
            stl.copyTo(File(dest, "libc++_shared.so"), overwrite = true)

            val clang = File(toolchain, "bin/$clangName")
            // PIE executable (not -shared): Android refuses to run a DYN with a
            // null entry point, so a -shared launcher dies with SIGSEGV=139 the
            // moment it is exec'd.
            val args = listOf(
                clang.absolutePath,
                "-std=c++17", "-fPIC", "-pie",
                "-I", File(nodeMobileDir, "include/node").absolutePath,
                layout.projectDirectory.file("src/main/cpp/noderunner.cpp").asFile.absolutePath,
                "-L", File(nodeMobileDir, "bin/$abi").absolutePath,
                "-lnode", "-llog",
                "-Wl,-rpath,\$ORIGIN",
                "-o", File(dest, "libnoderunner.so").absolutePath,
            )
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            if (code != 0) throw GradleException("noderunner ($abi) failed ($code):\n$output")
        }
    }
}

// ---------------------------------------------------------------------------
// BusyBox
//
// meefik's Android build (F-Droid) ships per-ABI `libbusybox.so`: a real,
// statically linked BusyBox compiled for Android. We stage it in jniLibs so
// Android extracts it to nativeLibraryDir and the mini-shell can exec applets
// (sed, awk, sort, tar, xargs, wget, ...).
// ---------------------------------------------------------------------------

val busyboxVersion = "1.34.1"
val busyboxApkName = "busybox-v1_34_1-52.apk"
val busyboxUrl =
    "https://github.com/meefik/busybox/releases/download/$busyboxVersion/$busyboxApkName"
val busyboxSha256 = "5972cc9ca5c36f789898f276d3c58a52d72583a7de14e7b3389cce087f45954c"
val busyboxDir: File = rootProject.file(".busybox")
val busyboxApk = File(busyboxDir, busyboxApkName)
val busyboxJniLibs = File(busyboxDir, "jniLibs")
val busyboxMarker = File(busyboxDir, ".unpacked-$busyboxVersion")

val fetchBusybox by tasks.registering {
    description = "Downloads Android BusyBox $busyboxVersion and stages per-ABI libbusybox.so."
    inputs.property("version", busyboxVersion)
    outputs.file(busyboxMarker)
    doLast {
        busyboxDir.mkdirs()
        if (!busyboxApk.isFile) {
            logger.lifecycle("PocketRun: downloading BusyBox $busyboxVersion from $busyboxUrl")
            val tmp = File(busyboxDir, "download.part")
            URI(busyboxUrl).toURL().openStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            tmp.renameTo(busyboxApk)
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(busyboxApk.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (actual != busyboxSha256) {
            busyboxApk.delete()
            throw GradleException("busybox: sha256 mismatch (got $actual); download deleted")
        }
        if (!busyboxMarker.isFile) {
            busyboxJniLibs.deleteRecursively()
            ZipInputStream(busyboxApk.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val abi = nodeMobileAbis.firstOrNull { entry.name == "lib/$it/libbusybox.so" }
                    if (abi != null && !entry.isDirectory) {
                        val dest = File(busyboxJniLibs, "$abi/libbusybox.so")
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out -> zin.copyTo(out) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            busyboxMarker.writeText(busyboxVersion)
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(assembleNodeJniLibs, fetchBusybox)
}

android {
    namespace = "dev.pocketrun"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    sourceSets["main"].jniLibs.srcDir(nodeMobileJniLibs)
    sourceSets["main"].jniLibs.srcDir(busyboxJniLibs)

    defaultConfig {
        applicationId = "dev.pocketrun"
        minSdk = 26
        targetSdk = 35
        versionCode = gitVersionCode()
        versionName = runGit("describe", "--tags", "--abbrev=0")?.removePrefix("v") ?: "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("String", "LICENSE_PUBKEY", "\"${licensePublicKey()}\"")
        buildConfigField("String", "SHARED_LICENSE_KEY", "\"${sharedLicenseKey()}\"")
        buildConfigField("String", "UPDATE_REPO", "\"${updateRepo()}\"")
    }

    signingConfigs {
        if (releasePropsFile.exists()) {
            val props = Properties()
            releasePropsFile.inputStream().use { props.load(it) }
            val storeFile = rootProject.file(
                props.getProperty("storeFile")
                    ?: throw GradleException("keystore.properties: storeFile is required")
            )
            if (!storeFile.isFile) {
                throw GradleException("keystore.properties: storeFile does not exist: $storeFile")
            }
            create("release") {
                this.storeFile = storeFile
                storePassword = props.getProperty("storePassword")
                    ?: throw GradleException("keystore.properties: storePassword is required")
                keyAlias = props.getProperty("keyAlias")
                    ?: throw GradleException("keystore.properties: keyAlias is required")
                keyPassword = props.getProperty("keyPassword")
                    ?: throw GradleException("keystore.properties: keyPassword is required")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
        jniLibs {
            // libnoderunner.so is executed, so it must be extracted to
            // nativeLibraryDir rather than loaded straight out of the APK.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The JVM file layer encodes paths with sun.jnu.encoding, which is ASCII on a
// C-locale runner; boot.js tests write files with Cyrillic names.
tasks.withType<Test>().configureEach {
    environment("LANG", "C.UTF-8")
    environment("LC_ALL", "C.UTF-8")
}

/**
 * The Python version is selected HERE, not via a Maven dependency: in Chaquopy
 * 17 a `com.chaquo.python:python` dependency is silently ignored for version
 * selection and the build falls back to the default (3.10). 3.13 or later is
 * also what supports devices with 16 KB memory pages.
 */
chaquopy {
    defaultConfig {
        version = "3.13"
    }
}

/**
 * The Ed25519 public key that license signatures are checked against. Baked into the APK,
 * so rotating it is a build change: put the key in android/license.pubkey (or override with
 * -PlicensePubkey=...) and rebuild.
 */
fun licensePublicKey(): String {
    val override = (project.findProperty("licensePubkey") as String?)?.trim()
    if (!override.isNullOrEmpty()) return override
    val file = rootProject.file("license.pubkey")
    if (file.isFile) return file.readText().trim()
    return "DEMO00000000000000000000000000000000000000000000000000000000000000"
}

/**
 * The key every build carries in it: the app activates itself with this one, so
 * a published release is usable by anyone without pasting anything. It is public
 * by design - whoever holds the APK can read it - so the license gate is a
 * formality here, not a protection.
 *
 * The file is written by tools/keygen (see docs/PLAN.md §2.3); keep it in sync
 * with license.pubkey, or the app falls back to the activation screen.
 */
fun sharedLicenseKey(): String {
    val override = (project.findProperty("sharedLicenseKey") as String?)?.trim()
    if (!override.isNullOrEmpty()) return override
    val file = rootProject.file("shared-license.key")
    if (!file.isFile) return ""
    val key = file.readText().trim()
    require('"' !in key && '\\' !in key) { "shared-license.key is not a plain PRK1 key" }
    return key
}

/** Where the in-app updater looks for releases: owner/name of the GitHub repository. */
fun updateRepo(): String {
    val override = (project.findProperty("updateRepo") as String?)?.trim()
    if (!override.isNullOrEmpty()) return override
    return "sj0404-collab/pocketrun"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.mozilla:rhino:1.8.1")
    implementation("net.i2p.crypto:eddsa:0.3.0")

    testImplementation("junit:junit:4.13.2")

    // Real org.json on the JVM: the android.jar stub throws "not mocked" in unit tests.
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
