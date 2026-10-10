// hidden-api 编译期占位模块。
//
// 目的：:app 需要子类化 framework 隐藏抽象类 android.window.DisplayWindowPolicyController
//（DWPC，DisplayWindowPolicyControllerHelper 持有它，用于 DisplayContent#canShowTasksInHostDeviceRecents
// 等判定）。SDK android.jar 不含该类，因此在这里提供一份「仅参与编译」的抽象签名 stub：
//
//   - :app 以 compileOnly(project(":hiddenapi")) 引用，stub 的类【不会】打进 APK；
//   - 运行时 DWPC 子类的父类解析按 parent-first 命中 boot classpath 的 ROM 真实类；
//   - 抽象签名已用真机反编译核对（Android 16：LineageOS lmi / ColorOS PLQ110 完全一致）。
//
// 不要把任何实现类放进本模块；也不要把本模块改为 implementation 依赖。

plugins {
    `java-library`
}

val sdkDir: String = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/Android/Sdk"

// 只需 ActivityInfo / Intent / IntentSender / ComponentName 等类型可见，任一较新平台即可。
val androidJar: File = listOf("android-37.0", "android-37.1", "android-36", "android-35", "android-34")
    .map { File(sdkDir, "platforms/$it/android.jar") }
    .firstOrNull { it.isFile }
    ?: throw GradleException("android.jar not found under $sdkDir/platforms (set ANDROID_HOME)")

dependencies {
    compileOnly(files(androidJar))
}
