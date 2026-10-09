import com.android.build.api.dsl.ApplicationExtension
import java.util.Base64

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// ---- 统一的 release 签名配置（密钥不入库）----
// 来源优先级：环境变量 > gradle.properties > 无。
//   RELEASE_KEYSTORE_FILE   : 已有的 .jks / .keystore 路径（本地常用）
//   RELEASE_KEYSTORE_BASE64 : keystore 文件的 base64（CI 用，配合 GitHub Secrets）
//   RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
// 四项任缺其一 => 不做签名配置，release 构建退化为未签名（不会因此失败）。
//
// 两个 application 模块（:app 模块本体、:probe 验收探测程序）都要签，
// 否则未签名产物名是 *-release-unsigned.apk，CI 按名取不到。
subprojects {
    plugins.withId("com.android.application") {
        fun envOrProp(name: String): String? =
            project.providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
                ?: project.providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

        val keystoreFile: File? = run {
            val direct = envOrProp("RELEASE_KEYSTORE_FILE")
            if (direct != null) return@run File(direct)
            val b64 = envOrProp("RELEASE_KEYSTORE_BASE64") ?: return@run null
            val out = File(project.layout.buildDirectory.get().asFile, "keystore/release.jks")
            out.parentFile.mkdirs()
            out.writeBytes(Base64.getDecoder().decode(b64.trim()))
            out
        }

        extensions.configure<ApplicationExtension> {
            if (keystoreFile != null) {
                signingConfigs {
                    create("release") {
                        storeFile = keystoreFile
                        storePassword = envOrProp("RELEASE_STORE_PASSWORD")
                        keyAlias = envOrProp("RELEASE_KEY_ALIAS")
                        keyPassword = envOrProp("RELEASE_KEY_PASSWORD")
                    }
                }
                buildTypes.getByName("release") {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
        }
    }
}
