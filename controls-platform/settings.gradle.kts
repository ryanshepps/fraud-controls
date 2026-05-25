pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "controls-platform"

include(
    ":modules:core",
    ":modules:features",
    ":modules:scoring",
    ":modules:rules",
    ":modules:decisioning",
    ":modules:persistence",
    ":modules:streaming",
    ":modules:api",
    ":modules:testing",
)
