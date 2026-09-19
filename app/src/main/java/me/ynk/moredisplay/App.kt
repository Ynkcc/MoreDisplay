package me.ynk.moredisplay

import android.app.Application
import android.content.Context
import android.util.Log
import me.ynk.moredisplay.data.DisplayRepository

class App : Application() {

    companion object {
        private const val TAG = "MoreDisplay_App"

        @Volatile
        private var repository: DisplayRepository? = null

        @Volatile
        private lateinit var appContext: Context

        val context: Context get() = appContext

        fun displays(): DisplayRepository = repository
            ?: DisplayRepository().also { repository = it }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        Log.i(TAG, "app start, lsposed injected=${me.ynk.moredisplay.xposed.LsposedBridge.isInjected}")
    }
}
