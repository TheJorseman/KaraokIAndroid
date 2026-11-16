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

    // ONNX Runtime (standard build). NOTE: the -qnn AAR variant was
    // measured producing degraded HTDemucs output on a Galaxy S24
    // Ultra (attenuated vocals -> vocal leakage in the instrumental),
    // so the stock build is used even though it lacks addQnn().
    api(libs.onnxruntime.android)
    // QNN backend runtime (Hexagon libs). Kept for experimentation;
    // the QNN backend option falls back to CPU when the EP is absent.
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
