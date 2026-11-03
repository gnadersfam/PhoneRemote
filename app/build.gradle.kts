plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.phoneremote.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneremote.app"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // A fixed key kept in the repo, so each new build installs over the old one.
    signingConfigs {
        create("remote") {
            storeFile = file("remote.jks")
            storePassword = "remote-open"
            keyAlias = "remote"
            keyPassword = "remote-open"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("remote") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("remote")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
