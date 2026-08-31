plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.droidlate.app"
    compileSdk = 35
    buildToolsVersion = "36.1.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.droidlate.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"

        manifestPlaceholders["appLabel"] = "Droidlate"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keyStorePath = (findProperty("RELEASE_STORE_FILE") as? String)
                ?: System.getenv("RELEASE_STORE_FILE")
            if (keyStorePath != null && file(keyStorePath).exists()) {
                storeFile = file(keyStorePath)
                storePassword = (findProperty("RELEASE_STORE_PASSWORD") as? String)
                    ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = (findProperty("RELEASE_KEY_ALIAS") as? String)
                    ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = (findProperty("RELEASE_KEY_PASSWORD") as? String)
                    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            } else {
                // Fallback to debug keystore for seamless local release builds
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
            manifestPlaceholders["appLabel"] = "Droidlate (Debug)"
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["appLabel"] = "Droidlate"
        }
    }

    lint {
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"

        sourceSets {
            getByName("main") {
                srcDir(layout.buildDirectory.dir("generated/python_src"))
            }
        }

        pip {
            install("flask>=3.0.0")
            install("requests>=2.31.0")
        }
    }
}

val syncPythonSources by tasks.registering(Sync::class) {
    from("${rootProject.projectDir}/droidlate")
    into(layout.buildDirectory.dir("generated/python_src/droidlate"))
}

tasks.matching { it.name.contains("Python") && it.name != "syncPythonSources" }.configureEach {
    dependsOn(syncPythonSources)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
}

