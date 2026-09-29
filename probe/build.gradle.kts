plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "me.ynk.moredisplay.probe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "me.ynk.moredisplay.probe"
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
