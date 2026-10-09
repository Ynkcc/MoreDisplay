plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.ynkcc.moredisplay.probe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.ynkcc.moredisplay.probe"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                // 与 :app 保持一致：开启 R8 后 probe-release 由 2.04 MB 降到约 0.5 MB。
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
}

// 签名配置见根 build.gradle.kts 的 subprojects 段：不签的话产物名会是
// probe-release-unsigned.apk，CI 里按名取不到。
