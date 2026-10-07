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

rootProject.name = "AHDownload"

include(":app")
include(":core:common")
include(":core:designsystem")
include(":domain")
include(":feature:welcome")

include(":feature:home")

include(":feature:downloads")
include(":feature:studio")
