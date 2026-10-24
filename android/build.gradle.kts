// AGP 9 has built-in Kotlin (bundled KGP 2.2.10). We pin a newer KGP and KSP on the build
// classpath, which is the officially documented way to upgrade them (see docs/COUNCIL_DECISIONS.md D-01).
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.google.services) apply false
}
