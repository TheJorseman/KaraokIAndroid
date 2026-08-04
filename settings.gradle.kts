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

rootProject.name = "KaraokIAndroid"

include(":app")

// core
include(":core:common")
include(":core:data")
include(":core:media")
include(":core:ai")
include(":core:whisper-jni")
include(":core:designsystem")

// feature
include(":feature:import")
include(":feature:library")
include(":feature:model-manager")
include(":feature:onboarding")
include(":feature:separation")
include(":feature:transcription")
include(":feature:karaoke-engine")
include(":feature:karaoke-player")
include(":feature:pipeline")

// asset pack
include(":fast-model-assetpack")
