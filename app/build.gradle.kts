import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// ---- release 签名（密钥不入库）----
// 来源优先级：环境变量 > gradle.properties > 无。
//   RELEASE_KEYSTORE_FILE   : 已有的 .jks / .keystore 路径（本地常用）
//   RELEASE_KEYSTORE_BASE64 : keystore 文件的 base64（CI 用，配合 GitHub Secrets）
//   RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
// 四项任缺其一 => 不做签名配置，release 构建退化为未签名（不会因此失败）。
fun envOrProp(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val releaseKeystore: File? = run {
    val direct = envOrProp("RELEASE_KEYSTORE_FILE")
    if (direct != null) return@run File(direct)
    val b64 = envOrProp("RELEASE_KEYSTORE_BASE64") ?: return@run null
    val out = File(project.layout.buildDirectory.get().asFile, "keystore/release.jks")
    out.parentFile.mkdirs()
    out.writeBytes(Base64.getDecoder().decode(b64.trim()))
    out
}

android {
    namespace = "io.github.ynkcc.moredisplay"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = releaseKeystore
                storePassword = envOrProp("RELEASE_STORE_PASSWORD")
                keyAlias = envOrProp("RELEASE_KEY_ALIAS")
                keyPassword = envOrProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.ynkcc.moredisplay"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // Xposed 模块的正常工作方式就是反射 framework 隐藏 API（DisplayManagerService、
        // VirtualDisplayConfig 等），lintVital 在 release 构建时把 BlockedPrivateApi 判为 fatal，
        // 这里按模块性质整体豁免。
        disable += "BlockedPrivateApi"
    }

    buildFeatures {
        aidl = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)
    compileOnly(libs.androidx.annotation)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// CI 用它取版本号：LSPosed 收录仓库要求 release tag 形如 `<versionCode>-<versionName>`。
// 版本值必须在配置期取出成局部变量，否则 task action 引用 `android` 会破坏 configuration cache。
val printedVersionName = android.defaultConfig.versionName
val printedVersionCode = android.defaultConfig.versionCode
tasks.register("printVersion") {
    val name = printedVersionName
    val code = printedVersionCode
    doLast {
        println("VERSION_NAME=$name")
        println("VERSION_CODE=$code")
    }
}