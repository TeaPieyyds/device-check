import java.util.Properties
import java.io.File as JFile

plugins {
    // AGP 9.0 起内置 Kotlin 支持，无需再 apply kotlin-android 插件
    alias(libs.plugins.android.application)
}

// ============ 签名信息解析 ============
// 支持两种来源：
//  A. KEYSTORE_FILE 指向已存在的 keystore 文件（推荐，workflow 先解码）
//  B. KEYSTORE_BASE64 直接给 base64，由脚本现场解码
// 另可用根目录 keystore.properties 做本地开发。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, envKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(key)

/**
 * 用反射调用 java.util.Base64 解码。
 * 不走 import / 全限定名，避免 Gradle Kotlin DSL 在 android{} 作用域内
 * 把 `java` 解析成 android 扩展属性（Unresolved reference 'util'）。
 */
fun decodeBase64ToFile(b64: String, out: JFile) {
    out.parentFile?.mkdirs()
    val decoder = Class.forName("java.util.Base64")
        .getMethod("getDecoder")
        .invoke(null)
    val bytes = decoder.javaClass
        .getMethod("decode", String::class.java)
        .invoke(decoder, b64) as ByteArray
    out.writeBytes(bytes)
}

val ksFromFile: String? = prop("storeFile", "KEYSTORE_FILE")
val ksFromB64: String? = prop("storeFileBase64", "KEYSTORE_BASE64")
val ksPwd: String? = prop("storePassword", "KEYSTORE_PASSWORD")
val ksAlias: String? = prop("keyAlias", "KEY_ALIAS")
val ksKeyPwd: String? = prop("keyPassword", "KEY_PASSWORD")

/** 最终用于签名的 keystore 文件（可能为 null） */
val resolvedKeystore: JFile? = run {
    if (ksPwd == null || ksAlias == null || ksKeyPwd == null) return@run null
    // 优先用现成文件
    val f = ksFromFile?.takeIf { it.isNotBlank() }?.let { JFile(it) }
    if (f != null && f.exists()) return@run f
    // 退而求其次：base64 现场解码
    if (!ksFromB64.isNullOrBlank()) {
        val out = JFile(rootProject.projectDir, "build/signing/release.jks")
        decodeBase64ToFile(ksFromB64, out)
        return@run out
    }
    null
}
val hasSigning: Boolean = resolvedKeystore != null

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
                storeFile = resolvedKeystore
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