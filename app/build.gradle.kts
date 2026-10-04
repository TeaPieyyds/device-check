plugins {
    // AGP 9.0 起内置 Kotlin 支持，无需再 apply kotlin-android 插件
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.teapieyyds.devicecheck"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.teapieyyds.devicecheck"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // 精简关键：代码混淆 + 资源压缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    // Shizuku：api 运行时必需；provider 与宿主共享
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}