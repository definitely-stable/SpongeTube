import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
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

rootProject.name = "SpongeTube"
include(":app")
include(":core:storage")
include(":core:engine")
include(":tools:media-lab")
include(":test-support:fixture-f1")

include(":playback:baseline")
include(":playback:bridge")

include(":benchmark")
