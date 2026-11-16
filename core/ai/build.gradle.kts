plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.karaokei.core.ai"
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
    implementation(project(":core:data"))

    implementation(libs.kotlinx.coroutines.android)

    // ONNX Runtime with the QNN EP compiled in (drop-in superset of
    // onnxruntime-android; exposes SessionOptions.addQnn()). The QNN
    // backend runtime ships the Hexagon libs and is optional at
    // runtime — without it addQnn() falls back to the CPU EP.
    api(libs.onnxruntime.android.qnn)
    api(libs.qnn.runtime)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
