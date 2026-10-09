plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.ynkcc.moredisplay"
    compileSdk {
        version = release(37)
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

// 签名配置见根 build.gradle.kts 的 subprojects 段。

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