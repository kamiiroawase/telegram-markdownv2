pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Single source of truth for dependency repositories (the project script declares none of
// its own); plugin repositories live in pluginManagement above. The default repositories
// mode (PREFER_PROJECT) resolves everything declared here as long as no project overrides it
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "telegram-markdownv2"
