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
        versionCode = 6
        versionName = "1.0.5"
        resourceConfigurations += listOf("zh", "en")
    }

    // ⚠️ 签名必须跨版本、跨环境完全一致，否则手机装新包会报
    //    INSTALL_FAILED_UPDATE_INCOMPATIBLE（只能卸载重装 -> 丢数据）。
    //
    // 这里**显式**创建 signingConfig，而不是依赖 AGP 的 debug 默认值：
    // 实测在 GitHub runner 上，即便 ~/.android/debug.keystore 已被正确还原
    // （文件大小与 sha256 都与本地一致），签出来的证书指纹仍与本地不同 ——
    // 所以把路径/口令都写成确定的、可被 CI 覆盖的形式。
    //
    // 自用工具，复用 debug 级密钥；需要正式签名时换掉这段即可。
    // ⚠️ 不要在这里写 `java.io.File(...)`：Kotlin DSL 脚本里 `java` 会被
    //    JavaPluginExtension 的访问器遮蔽，直接报 `Unresolved reference: io`。
    //    改用 Gradle 的 `file()`（等价，且无需任何 import）。
    signingConfigs {
        create("fixed") {
            val home = System.getProperty("user.home") ?: "."
            val ksPath = System.getenv("BT_KEYSTORE") ?: "$home/.android/debug.keystore"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("BT_KEYSTORE_STORE_PASS") ?: "android"
                keyAlias = System.getenv("BT_KEYSTORE_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("BT_KEYSTORE_KEY_PASS") ?: "android"
            } else {
                logger.warn("[bt-monitor] 未找到密钥库 $ksPath，将回退到 AGP 默认 debug 签名")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (signingConfigs.getByName("fixed").storeFile != null)
                signingConfigs.getByName("fixed") else signingConfigs.getByName("debug")
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
