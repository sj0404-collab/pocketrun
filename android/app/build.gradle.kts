import org.gradle.api.GradleException
import java.util.Properties

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

android {
    namespace = "dev.pocketrun"
    compileSdk = 35

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
