pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "slidedify"

include(":app")

// Pure Kotlin: no Android dependency, unit-tested on the JVM.
include(":core:model")
include(":core:domain")
include(":lyrics:lrclib")
include(":preview:deezer")

// Android libraries.
include(":core:designsystem")
include(":core:datastore")
include(":core:data")
include(":core:platform")
include(":core:media")
include(":feature:source")
include(":feature:swipe")
include(":feature:settings")

// Music providers. Each one implements MusicProvider and registers itself via Hilt.
include(":provider:fake")
include(":provider:spotify-api")
include(":provider:spotify")
