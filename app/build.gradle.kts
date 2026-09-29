plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.finevolume.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.finevolume.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.icons.extended)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // DataStore (Preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Hidden API bypass
    implementation(libs.hidden.api.bypass)

    // Coroutines
    implementation(libs.coroutines.android)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    androidTestImplementation(libs.mockk.android)
}

tasks.register("copyArtifactsToDesktop") {
    doLast {
        val userHome = System.getProperty("user.home")
        val desktop = file("$userHome/Desktop")
        if (desktop.exists()) {
            fileTree(layout.buildDirectory.dir("outputs/apk")).matching {
                include("**/*.apk")
            }.forEach { apkFile ->
                val targetName = if (apkFile.name.contains("release")) "FineVolume-release.apk" else "FineVolume-debug.apk"
                apkFile.copyTo(File(desktop, targetName), overwrite = true)
            }
            fileTree(layout.buildDirectory.dir("outputs/bundle")).matching {
                include("**/*.aab")
            }.forEach { aabFile ->
                val targetName = if (aabFile.name.contains("release")) "FineVolume-release.aab" else "FineVolume-debug.aab"
                aabFile.copyTo(File(desktop, targetName), overwrite = true)
            }
        }
    }
}

tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }.configureEach {
    finalizedBy("copyArtifactsToDesktop")
}

