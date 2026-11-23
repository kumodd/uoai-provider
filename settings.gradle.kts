pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "UniversalOfflineAI"
include(
    ":provider-app", ":core", ":inference", ":provider-api", ":client-management",
    ":plugins", ":android-integration", ":sdk", ":sample-client",
)
