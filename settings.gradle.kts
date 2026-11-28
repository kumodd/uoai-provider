pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "UniversalOfflineAI"
include(
    ":provider-app", ":core", ":inference", ":asr", ":provider-api", ":client-management",
    ":plugins", ":android-integration", ":sdk",
)
if (file("sample-client").isDirectory) {
    include(":sample-client")
}
