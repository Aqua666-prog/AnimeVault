plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sergey.animevault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sergey.animevault.discovery"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "5.0-discovery"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        kotlin.directories.add("../app/src/debug/java/com/sergey/animevault/discovery")
        res.srcDir("../app/src/debug/res")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
