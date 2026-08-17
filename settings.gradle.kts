pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.") && requested.version != null) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        maven {
            url = uri("http://127.0.0.1:18080/unified")
            isAllowInsecureProtocol = true
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("http://127.0.0.1:18080/unified")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "AnimeVault"
include(":app")
