pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "NotiSift"
include(":app")
// Debug helper that posts chat-like notifications with deep links, for end-to-end tests (see tools/testsender).
include(":testsender")
project(":testsender").projectDir = file("tools/testsender")
