import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * YouTube Music needs a Google Cloud project with the YouTube Data API enabled and an Android OAuth
 * client for this package + signing SHA-1 (no client id goes in the app). Set YOUTUBE_ENABLED=true
 * once that exists; until then the provider is not offered.
 */
val youtubeEnabled: Boolean = (
    providers.gradleProperty("YOUTUBE_ENABLED").orNull
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use(::load) }.getProperty("YOUTUBE_ENABLED")
        }
        ?: providers.environmentVariable("YOUTUBE_ENABLED").orNull
    ).toBoolean()

android {
    namespace = "com.slidedify.provider.youtube"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        buildConfigField("boolean", "YOUTUBE_ENABLED", "$youtubeEnabled")
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
    implementation(project(":provider:youtube-api"))
    implementation(libs.play.services.auth)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
