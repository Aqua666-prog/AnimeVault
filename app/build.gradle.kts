plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val animeVaultDebugKeystorePath = providers.gradleProperty("animeVaultDebugKeystore")
    .orElse(providers.environmentVariable("ANIMEVAULT_DEBUG_KEYSTORE"))
val animeVaultDebugStorePassword = providers.gradleProperty("animeVaultDebugStorePassword")
    .orElse(providers.environmentVariable("ANIMEVAULT_DEBUG_STORE_PASSWORD"))
    .orElse("android")
val animeVaultDebugKeyAlias = providers.gradleProperty("animeVaultDebugKeyAlias")
    .orElse(providers.environmentVariable("ANIMEVAULT_DEBUG_KEY_ALIAS"))
    .orElse("androiddebugkey")
val animeVaultDebugKeyPassword = providers.gradleProperty("animeVaultDebugKeyPassword")
    .orElse(providers.environmentVariable("ANIMEVAULT_DEBUG_KEY_PASSWORD"))
    .orElse("android")
val animeVaultReleaseKeystorePath = providers.gradleProperty("animeVaultReleaseKeystore")
    .orElse(providers.environmentVariable("ANIMEVAULT_RELEASE_KEYSTORE"))
val animeVaultReleaseStorePassword = providers.gradleProperty("animeVaultReleaseStorePassword")
    .orElse(providers.environmentVariable("ANIMEVAULT_RELEASE_STORE_PASSWORD"))
val animeVaultReleaseKeyAlias = providers.gradleProperty("animeVaultReleaseKeyAlias")
    .orElse(providers.environmentVariable("ANIMEVAULT_RELEASE_KEY_ALIAS"))
val animeVaultReleaseKeyPassword = providers.gradleProperty("animeVaultReleaseKeyPassword")
    .orElse(providers.environmentVariable("ANIMEVAULT_RELEASE_KEY_PASSWORD"))

android {
    namespace = "com.sergey.animevault"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sergey.animevault"
        minSdk = 24
        targetSdk = 37
        versionCode = 45
        versionName = "1.7.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        getByName("debug") {
            animeVaultDebugKeystorePath.orNull
                ?.takeIf { it.isNotBlank() }
                ?.let { configuredPath ->
                    storeFile = rootProject.file(configuredPath)
                    storePassword = animeVaultDebugStorePassword.get()
                    keyAlias = animeVaultDebugKeyAlias.get()
                    keyPassword = animeVaultDebugKeyPassword.get()
                }
        }
        create("release") {
            animeVaultReleaseKeystorePath.orNull?.takeIf(String::isNotBlank)?.let { configuredPath ->
                storeFile = rootProject.file(configuredPath)
                storePassword = animeVaultReleaseStorePassword.orNull
                keyAlias = animeVaultReleaseKeyAlias.orNull
                keyPassword = animeVaultReleaseKeyPassword.orNull
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/androidx/**/LICENSE.txt",
            "META-INF/kotlin-project-structure-metadata.json",
            "**/*.knm",
            "**/*.kotlin_metadata",
            "**/linkdata/**",
            "commonMain/**",
            "commonTest/**",
            "nativeMain/**",
            "nonJvmMain/**",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
    // CoroutineWorker внутри WorkManager использует ListenableFutureKt из
    // concurrent-futures-ktx. Нельзя исключать эту транзитивную зависимость:
    // APK соберётся, но любой CoroutineWorker упадёт при создании.
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.squareup.okio:okio-jvm:3.17.0")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.google.truth:truth:1.4.5")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}
