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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AIChallenge"
include(":app")
include(":core:designsystem")
include(":core:mvvm")
include(":core:network")
include(":core:utils")
include(":feature:common")
include(":feature:home")
include(":feature:prompt-lab")
include(":feature:temperature-lab")
include(":feature:huggingface-lab")
