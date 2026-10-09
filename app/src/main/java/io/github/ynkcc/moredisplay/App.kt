package io.github.ynkcc.moredisplay

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.ynkcc.moredisplay.data.DisplayRepository

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
        Log.i(TAG, "app start, lsposed injected=${io.github.ynkcc.moredisplay.xposed.LsposedBridge.isInjected}")
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
            Log.i(TAG, "shizuku binder received, ping=${rikka.shizuku.Shizuku.pingBinder()}")
        }
    }
}
