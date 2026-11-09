pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// Lets Gradle download the JDK 17 that the daemon (gradle-daemon-jvm.properties) and toolchains need.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") { content { includeGroupByRegex("ai\\.pleos\\.playground.*") } }
    }
}
rootProject.name = "EasyCar"
include(":app", ":core")
