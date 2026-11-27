pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AnimeVault"
if (providers.gradleProperty("animeVaultDiscoveryApk").orNull == "true") {
    include(":discovery")
} else {
    include(":app")
    if (providers.gradleProperty("animeVaultBenchmarks").orNull == "true") {
        include(":macrobenchmark")
    }
}
