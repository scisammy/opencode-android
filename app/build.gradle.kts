plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "1.9.21"
}

android {
    namespace = "com.ubuntuterminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ubuntuterminal"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
