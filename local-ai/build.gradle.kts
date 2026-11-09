plugins {
    id("com.android.library")
}

// Keep the unfinished inference runtime out of ordinary builds during stage 1.
// The explicit engineering build packages the real ARM64 runtime.
val includeMnnRuntime = providers.gradleProperty("includeMnnRuntime")
    .map { it.toBooleanStrict() }.orElse(false).get()

android {
    namespace = "com.sergey.animevault.localai"
    compileSdk = 37
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        if (includeMnnRuntime) {
            externalNativeBuild {
                cmake {
                    abiFilters += "arm64-v8a"
                    arguments += "-DANDROID_STL=c++_shared"
                    targets += listOf("animevault_ai", "MNN")
                }
            }
        }
    }
    if (includeMnnRuntime) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
