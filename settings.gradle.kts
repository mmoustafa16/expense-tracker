pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "expense-tracker"

include(
    ":core:money",
    ":core:sms",
    ":core:parse",
    ":core:intelligence",
    ":core:merchants",
    ":core:categories",
    ":core:ledger",
    ":core:ingest",
    ":android:capture",
    ":android:storage",
    ":android:ui-common",
    ":android:ui-unlock",
    ":android:ui-review",
    ":android:ui-ledger",
    ":android:ui-search",
    ":android:ui-analytics",
    ":android:app",
)
