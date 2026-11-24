enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Poolakey (Bazaar) is published on JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\..*") }
        }
        // Myket's official billing SDK (ir.myket:billing).
        // https://myket.ir/docs/technical/iab/android/
        maven {
            url = uri("https://maven.myket.ir")
            content { includeGroup("ir.myket") }
        }
    }
}

rootProject.name = "Fenjoon"
include(":app")
