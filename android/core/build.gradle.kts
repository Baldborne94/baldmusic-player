import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Logica pura, senza Android: client Subsonic, albero della libreria, ID dei media.
// Sta in un modulo JVM a parte così i test girano senza emulatore né SDK.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.1.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.1.0")
}

tasks.test { useJUnitPlatform() }
