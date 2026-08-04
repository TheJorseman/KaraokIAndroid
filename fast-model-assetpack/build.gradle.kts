plugins {
    alias(libs.plugins.android.asset.pack)
}

// Asset Pack configuration (T0.5 / T2.5).
// `asset-pack` plugin doesn't have a top-level DSL; configuration is done
// via the Android Gradle Plugin's Bundle DSL in :app/build.gradle.kts and
// the assetpack.gradle file referenced from the project root.
//
// This file is intentionally minimal: the actual asset content lives in
// src/main/assets/ and is documented by T-PRE.1 / T2.5.
