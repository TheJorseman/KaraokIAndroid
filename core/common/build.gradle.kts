plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// No forzar toolchain JDK 17 (requiere un JDK 17 instalado). Compilamos
// con el JDK actual (21) hacia bytecode 17. Estos módulos son consumidos
// por módulos Android y su bytecode se dexa con d8/r8, que soporta
// class files 17.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
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
