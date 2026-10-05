import java.util.Properties

plugins {
    // AGP 9.0 起内置 Kotlin 支持，无需再 apply kotlin-android 插件
    alias(libs.plugins.android.application)
}

// 读取签名信息：优先环境变量（CI），其次 keystore.properties（本地），都没有则 release 不签名
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(key)

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
        create("release") {
            val storeB64 = secret("storeFileBase64", "KEYSTORE_BASE64")
            val storePwd = secret("storePassword", "KEYSTORE_PASSWORD")
            val aliasVal = secret("keyAlias", "KEY_ALIAS")
            val keyPwd = secret("keyPassword", "KEY_PASSWORD")
            if (storeB64 != null && storePwd != null && aliasVal != null && keyPwd != null) {
                // 把 base64 还原成临时 keystore 文件
                val out = file("$buildDir/keystore/release.jks")
                out.parentFile.mkdirs()
                out.writeBytes(java.util.Base64.getDecoder().decode(storeB64))
                storeFile = out
                storePassword = storePwd
                keyAlias = aliasVal
                keyPassword = keyPwd
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
            // 有签名信息才签名；没有则产出 unsigned（本地调试用）
            if (System.getenv("KEYSTORE_BASE64") != null || keystoreProps.containsKey("storeFileBase64")) {
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