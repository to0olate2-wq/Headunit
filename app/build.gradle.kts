plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// +1000 so builds from this repo always install over the earlier test builds
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.drivelauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.drivelauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1000 + runNumber
        versionName = "0.6.$runNumber"
    }

    // One fixed key so every new build installs over the old one (settings are kept).
    signingConfigs {
        create("shared") {
            storeFile = file("../signing/drive-launcher.keystore")
            storePassword = "drivelauncher"
            keyAlias = "drive"
            keyPassword = "drivelauncher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "2g" }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
