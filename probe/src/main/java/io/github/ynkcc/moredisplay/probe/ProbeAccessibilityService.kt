package io.github.ynkcc.moredisplay.probe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 需求 C 的「探测无障碍服务」——一个**普通 App** 的无障碍服务（不被模块注入）。
 *
 * 只做一件事：按 adb 广播指令派发手势，把派发目标与结果打到 logcat，
 * 供对照 system_server 侧 `MoreDisplay_A11y` 的改写日志。
 *
 * 用法（adb）：
 * ```
 * # 1. 开启服务
 * adb shell settings put secure enabled_accessibility_services \
 *     io.github.ynkcc.moredisplay.probe/io.github.ynkcc.moredisplay.probe.ProbeAccessibilityService
 * adb shell settings put secure accessibility_enabled 1
 *
 * # 2. 派发手势（默认走 GestureDescription 默认 displayId=0）
 * adb shell am broadcast -a io.github.ynkcc.moredisplay.probe.GESTURE --ef x 540 --ef y 960
 *
 * # 3. 显式指定目标屏（对照：hook 不应改写显式指向非默认屏的手势）
 * adb shell am broadcast -a io.github.ynkcc.moredisplay.probe.GESTURE \
 *     --ef x 540 --ef y 960 --ei display 0
 *
 * adb logcat -s MoreDisplay_Probe MoreDisplay_A11y
 * ```
 */
class ProbeAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "MoreDisplay_Probe"

        const val ACTION_GESTURE = "io.github.ynkcc.moredisplay.probe.GESTURE"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_DISPLAY = "display"
        private const val TAP_MS = 50L
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_GESTURE -> dispatchTap(
                    intent.getFloatExtra(EXTRA_X, 100f),
                    intent.getFloatExtra(EXTRA_Y, 100f),
                    intent.getIntExtra(EXTRA_DISPLAY, Int.MIN_VALUE)
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 广播来自 adb shell（shell uid），必须导出；Android 14+ 要求显式指定标志。
        registerReceiver(receiver, IntentFilter(ACTION_GESTURE), Context.RECEIVER_EXPORTED)
        Log.i(TAG, "probe accessibility service connected pid=${android.os.Process.myPid()}")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun dispatchTap(x: Float, y: Float, displayId: Int) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_MS)
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (displayId != Int.MIN_VALUE) {
            builder.setDisplayId(displayId)
        }
        val description = builder.build()
        Log.i(
            TAG,
            "GESTURE dispatch x=$x y=$y requestedDisplay=${description.displayId}"
        )
        val dispatched = dispatchGesture(
            description,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "GESTURE completed display=${gestureDescription?.displayId}")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(
                        TAG,
                        "GESTURE cancelled display=${gestureDescription?.displayId} " +
                            "(injector 不存在或被系统拒绝)"
                    )
                }
            },
            null
        )
        Log.i(TAG, "GESTURE dispatchGesture returned=$dispatched")
    }
}
