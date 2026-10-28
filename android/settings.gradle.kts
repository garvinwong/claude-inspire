// Claude Inspire（掌上灵感）安卓工程：AGP 8.5.2 + Kotlin 2.0.20
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "claude-inspire"
include(":app")
