plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Igual que :core:common — evitar toolchain JDK 17 estricto.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.javax.inject)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit5.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
}

// JUnit 5 tests need the JUnit Platform; without this the default
// JUnit 4 runner silently executes zero tests.
tasks.test {
    useJUnitPlatform()
}
