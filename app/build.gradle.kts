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
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                // 开启 R8：未开启时 dex 达 23.4 MB（占 APK 90%），
                // Compose / Material3 / AndroidX / Kotlin stdlib 全量打包。
                // AGP 9 起 minifyEnabled / shrinkResources 已统一由该开关控制。
                enable = true
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

// AGP 9.x 会在 classpath 内嵌自己的 KGP（本例为 2.2.10），compose 插件据此解析
// composeMappingProducerClasspath 中的 org.jetbrains.kotlin:compose-group-mapping，
// 但该构件自 2.4.x 才开始发布，2.2.10 不存在 => 全量 assemble（如 CodeQL autobuild）失败。
// 强制对齐到项目实际使用的 KGP 版本。
configurations.matching { it.name == "composeMappingProducerClasspath" }.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:compose-group-mapping:${libs.versions.kotlin.get()}")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    // 仅预览用，不进 release 包。
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // icons 不再由 BOM / material3 传递提供，必须显式引入
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 全 Compose 项目：不引入 View 版 Material Components 与 androidx.appcompat，
    // 二者未开启 R8 时会贡献数 MB dex，且项目中没有任何 View 体系代码使用。
    implementation(libs.androidx.core.ktx)
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)
    compileOnly(libs.androidx.annotation)
    // DWPC（android.window.DisplayWindowPolicyController）隐藏类抽象签名，
    // 仅编译期可见、不进 APK；运行时父类解析命中 boot classpath 的 ROM 真实类。
    compileOnly(project(":hiddenapi"))
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