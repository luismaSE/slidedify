import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Client ID from the Spotify Developer Dashboard, read (in order) from the `SPOTIFY_CLIENT_ID`
 * Gradle property, `local.properties` or environment variable. It is not a secret (PKCE has no
 * client secret), but it stays out of the repo so each developer/CI uses their own app.
 * When empty, the Spotify provider is simply not registered.
 */
val spotifyClientId: String = providers.gradleProperty("SPOTIFY_CLIENT_ID").orNull
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }.getProperty("SPOTIFY_CLIENT_ID")
    }
    ?: providers.environmentVariable("SPOTIFY_CLIENT_ID").orNull
    ?: ""

android {
    namespace = "com.slidedify.provider.spotify"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"slidedify://spotify-callback\"")
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
    implementation(project(":provider:spotify-api"))

    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
