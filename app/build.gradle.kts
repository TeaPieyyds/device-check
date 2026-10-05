import java.util.Properties

plugins {
    // AGP 9.0 起内置 Kotlin 支持，无需再 apply kotlin-android 插件
    alias(libs.plugins.android.application)
}

// 签名信息来源（按优先级）：
//  1. 环境变量 KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD（CI 用，文件由 workflow 提前还原）
//  2. 根目录 keystore.properties（本地用）
// 都没有则 release 不签名（产出 unsigned）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(key)

val ksPath: String? = prop("storeFile", "KEYSTORE_FILE")
val ksPwd: String? = prop("storePassword", "KEYSTORE_PASSWORD")
val ksAlias: String? = prop("keyAlias", "KEY_ALIAS")
val ksKeyPwd: String? = prop("keyPassword", "KEY_PASSWORD")
val hasSigning: Boolean =
    !ksPath.isNullOrBlank() && ksPwd != null && ksAlias != null && ksKeyPwd != null

android {
    namespace = "com.teapieyyds.devicecheck"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.teapieyyds.devicecheck"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(ksPath!!)
                storePassword = ksPwd
                keyAlias = ksAlias
                keyPassword = ksKeyPwd
            }
        }
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
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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