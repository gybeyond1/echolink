plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.notifysync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notifysync"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包真机常用 ABI，剔除 x86 / x86_64，显著减小安装包体积
        // （io.getstream:stream-webrtc-android 原生库占大头，约 40MB+）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("fixed") {
            val keystoreFile = System.getenv("KEYSTORE_FILE") ?: "notifysync.p12"
            storeFile = file(keystoreFile)
            storeType = "PKCS12"
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // RecyclerView & ViewPager2
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // 下拉刷新
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // OkHttp (HTTP + WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebRTC（P2P 打洞直传文件；Google 官方 google-webrtc 已随 JCenter 下架，用 getstream 维护版）
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON parsing
    implementation("org.json:json:20240303")
}
