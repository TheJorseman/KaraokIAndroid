// Top-level build file. Module-level config lives in each module's build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.asset.pack) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
}

// NOTE on detekt (T0.4):
//
// The detekt plugin needs to be applied per-module via its own
// `plugins { alias(libs.plugins.detekt) }` block. Trying to apply it
// imperatively from `subprojects { ... }` doesn't work because the
// plugin's classes are only on the buildscript classpath of the root
// when declared `apply false`, and the `Detekt` task type cannot be
// resolved from the subproject's class loader at script-compile time.
//
// Each module that wants detekt should add:
//     plugins { alias(libs.plugins.detekt) }
//     detekt { toolVersion = "1.23.7" }
// to its own build.gradle.kts. For the MVP, detekt is optional and is
// not applied; re-enable it module-by-module once the project is up
// and running.
