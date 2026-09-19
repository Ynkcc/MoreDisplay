package me.ynk.moredisplay.dispatch

object AndroidVersions {
    const val API_29_ANDROID_10 = android.os.Build.VERSION_CODES.Q
    const val API_30_ANDROID_11 = android.os.Build.VERSION_CODES.R
    const val API_31_ANDROID_12 = android.os.Build.VERSION_CODES.S
    const val API_32_ANDROID_12L = android.os.Build.VERSION_CODES.S_V2
    const val API_33_ANDROID_13 = android.os.Build.VERSION_CODES.TIRAMISU
    const val API_34_ANDROID_14 = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    const val API_35_ANDROID_15 = android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM

    val SDK_INT: Int get() = android.os.Build.VERSION.SDK_INT
}
