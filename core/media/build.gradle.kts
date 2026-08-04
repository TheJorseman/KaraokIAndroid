plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.karaokei.core.media"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.android)

    // Media3
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.common)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.transformer)
    // media3-decoder-ffmpeg: no publicada en Maven Central (compilar desde
    // androidx/media). Ver docs/post-mvp.md.

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
