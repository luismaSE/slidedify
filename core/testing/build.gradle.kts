import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Shared fakes for unit tests (a music service, settings, progress). Only used from tests. */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:domain"))
}
