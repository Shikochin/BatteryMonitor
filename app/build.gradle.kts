plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Versioning
// ---------------------------------------------------------------------------
// versionName is fixed at 1.0 — do not derive it. Only versionCode moves: it is
// the number of commits reachable from HEAD, so every commit produces an
// installable upgrade with no manual edit.
//
// versionCode priority:
//   -PversionCode=123        (Gradle property)
//   VERSION_CODE=123         (environment variable)
//   git rev-list --count HEAD (default)
//   1                        (no git checkout, e.g. a source tarball)
fun gitCommitCount(): Int? = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
}.getOrNull()

val buildNumber: Int =
    providers.gradleProperty("versionCode").orNull?.toIntOrNull()
        ?: providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull()
        ?: gitCommitCount()
        ?: 1

// Fixed on purpose: the app is versioned by commit count, not by this string.
val appVersionName = "1.0"

android {
    namespace = "dev.shiko.batterymonitor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.shiko.batterymonitor"
        minSdk = 29
        targetSdk = 37
        // Commit count: monotonic, so `adb install -r` always accepts the new APK.
        versionCode = buildNumber
        versionName = appVersionName

        // Single ABI: the app ships for 64-bit ARM devices only.
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Signed with the debug key on purpose: the release build is for
            // sideloading (R8-optimised), not for Play distribution.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

// Lets CI (and you) read the derived version without building:
//   ./gradlew -q :app:printVersion
tasks.register("printVersion") {
    group = "help"
    description = "Prints the derived versionCode/versionName."
    val code = buildNumber
    val name = appVersionName
    doLast {
        println("versionCode=$code")
        println("versionName=$name")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui.tooling.preview)

    implementation(libs.miuix)
    implementation(libs.miuix.icons)
    // Backdrop blur for the collapsing top app bar.
    implementation(libs.miuix.blur)

    // Shizuku: lets the app run shell commands (dumpsys batterystats / battery) with the shell uid
    // so per-app power figures come from the system's own accounting instead of being estimated.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
