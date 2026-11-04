// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Puts a newer Kotlin Gradle Plugin on the build classpath without applying it:
    // AGP 9's built-in Kotlin support reads the KGP version from this classpath.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
