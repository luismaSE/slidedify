import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Free Last.fm API key (https://www.last.fm/api/account/create), for style tags in suggestions.
 * Read from the `LASTFM_API_KEY` Gradle property, `local.properties` or environment variable.
 * Without it, suggestions still work from artists, tempo, audio and playlist names.
 */
val lastFmApiKey: String = providers.gradleProperty("LASTFM_API_KEY").orNull
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }.getProperty("LASTFM_API_KEY")
    }
    ?: providers.environmentVariable("LASTFM_API_KEY").orNull
    ?: ""

/** Reads a setting from a Gradle property, `local.properties` or an environment variable, in that order. */
fun setting(name: String): String? = providers.gradleProperty(name).orNull
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }.getProperty(name)
    }
    ?: providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

/**
 * Release signing. The keystore never goes in the repo: CI decodes it from a secret to a temp file
 * (see .github/workflows/ci.yml). Without it, release builds are signed with the shared debug key,
 * so they still build and install (but can't go to Play Store).
 */
val releaseKeystore: String? = setting("RELEASE_KEYSTORE_PATH")?.takeIf { rootProject.file(it).exists() || File(it).exists() }

/** Each CI run gets a higher number, so a new APK always installs over the previous one. */
val appVersionCode: Int = setting("VERSION_CODE")?.toIntOrNull() ?: 1
val appVersionName: String = setting("VERSION_NAME") ?: "0.1.0"

android {
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "com.slidedify.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.slidedify.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmApiKey\"")
    }

    signingConfigs {
        // Shared debug key (not secret) so CI builds can be installed over each other.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = File(releaseKeystore).takeIf { it.isAbsolute } ?: rootProject.file(releaseKeystore)
                storePassword = setting("RELEASE_STORE_PASSWORD")
                keyAlias = setting("RELEASE_KEY_ALIAS")
                keyPassword = setting("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:datastore"))
    implementation(project(":core:data"))
    implementation(project(":core:platform"))
    implementation(project(":preview:deezer"))
    implementation(project(":suggest:lastfm"))
    implementation(project(":core:media"))
    implementation(project(":feature:source"))
    implementation(project(":feature:connections"))
    implementation(project(":feature:swipe"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:transfer"))

    // Providers bundled in this build. Adding one = one line here + its own module.
    implementation(project(":provider:fake"))
    implementation(project(":provider:spotify")) // registers itself only if SPOTIFY_CLIENT_ID is set
    implementation(project(":provider:tidal")) // only if TIDAL_CLIENT_ID is set
    implementation(project(":provider:youtube")) // only if YOUTUBE_ENABLED=true

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
