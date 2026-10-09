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
                enable = false
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
