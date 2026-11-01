import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** TIDAL developer app client id (developer.tidal.com). Empty = TIDAL is not offered in the app. */
val tidalClientId: String = providers.gradleProperty("TIDAL_CLIENT_ID").orNull
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }.getProperty("TIDAL_CLIENT_ID")
    }
    ?: providers.environmentVariable("TIDAL_CLIENT_ID").orNull
    ?: ""

android {
    namespace = "com.slidedify.provider.tidal"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        buildConfigField("String", "TIDAL_CLIENT_ID", "\"$tidalClientId\"")
        buildConfigField("String", "TIDAL_REDIRECT_URI", "\"slidedify://tidal-callback\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:platform"))
    implementation(project(":core:media"))
    implementation(project(":provider:tidal-api"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
