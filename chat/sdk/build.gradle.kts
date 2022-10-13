plugins {
    id("com.android.library")
    kotlin("android")
    id("com.squareup.sqldelight")
    id("com.google.devtools.ksp") version kspVersion
    id("publish-module-android")
}

project.apply {
    extra[KEY_PUBLISH_ARTIFACT_ID] = "chat"
    extra[KEY_PUBLISH_VERSION] = "1.0.0-alpha01"
    extra[KEY_SDK_VERSION_CODE] = extra.get(KEY_PUBLISH_VERSION).toString().toHash()
    extra[KEY_SDK_NAME] = "Chat"
}

android {
    compileSdk = COMPILE_SDK

    defaultConfig {
        minSdk = MIN_SDK
        targetSdk = TARGET_SDK

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    debugImplementation(project(":androidCore:impl"))
    releaseImplementation("com.walletconnect:android-core-impl:1.0.0")

    retrofit()
    navigationComponent()
    moshiKsp()

    androidXTest()
    jUnit5()
    jUnit5Android()
    robolectric()
    mockk()
    testJson()
    coroutinesTest()
    scarletTest()
    sqlDelightTest()
}