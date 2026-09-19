plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lhj.btmonitor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lhj.btmonitor"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        resourceConfigurations += listOf("zh", "en")
    }

    // 自用监控工具：直接复用 AGP 内置的 debug 签名配置。
    // 不在这里手动指定 storeFile —— 交给 AGP 自己生成/复用 ~/.android/debug.keystore，
    // 否则在 CI（全新环境、没有该文件）上会因 keystore 缺失而构建失败。
    // 需要正式签名时，在此新增 signingConfigs 并把 release 指向它即可。

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
