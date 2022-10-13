plugins {
    id("com.android.library")
    kotlin("android")
    id("publish-module-android")
}

project.apply {
    extra[KEY_PUBLISH_ARTIFACT_ID] = "android-core"
    extra[KEY_PUBLISH_VERSION] = "1.0.0"
    extra[KEY_SDK_VERSION_CODE] = extra.get(KEY_PUBLISH_VERSION).toString().toHash()
    extra[KEY_SDK_NAME] = "Android Core"
}

android {
    compileSdk = COMPILE_SDK

    defaultConfig {
        minSdk = MIN_SDK
        targetSdk = TARGET_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = jvmVersion
        targetCompatibility = jvmVersion
    }

    kotlinOptions {
        jvmTarget = jvmVersion.toString()
    }
}

dependencies {
    debugApi(project(":androidCore:common"))
    releaseApi("com.walletconnect:android-core-common:1.0.0")

    koinAndroid()
}