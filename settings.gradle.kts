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

rootProject.name = "expense-tracker"

include(
    ":core:money",
    ":core:sms",
    ":core:parse",
    ":core:merchants",
    ":core:categories",
    ":core:ledger",
    ":core:ingest",
)
