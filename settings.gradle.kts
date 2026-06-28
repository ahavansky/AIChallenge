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
include(":feature:agent-chat")
include(":feature:context-agent")
include(":feature:home")
include(":feature:prompt-lab")
include(":feature:temperature-lab")
include(":feature:huggingface-lab")
include(":mcp:github-server")
include(":mcp:dev-build-server")
include(":mcp:dev-common")
include(":mcp:dev-device-server")
include(":mcp:dev-project-server")
include(":mcp:live-briefing-server")
include(":mcp:pipeline-common")
include(":mcp:pipeline-search-server")
include(":mcp:pipeline-summarize-server")
include(":mcp:pipeline-save-server")
