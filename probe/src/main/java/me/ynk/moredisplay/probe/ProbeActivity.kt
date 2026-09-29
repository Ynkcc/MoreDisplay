package me.ynk.moredisplay.probe

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 屏幕可见性「探测程序」—— 一个**普通 App**（不被模块注入、不在 LSPosed 作用域内）。
 *
 * 它只用公开 API 复现一个目标 App 能做的全部「探测屏幕」动作：
 * 1. 列表：`DisplayManager.getDisplays()` / `getDisplays(DISPLAY_CATEGORY_PRESENTATION)`；
 * 2. 猜 id：`getDisplay(0..MAX_GUESS)`，逐个点名（命中策略时平台返回 null）；
 * 3. 事件：注册 `DisplayListener`，观察 `onDisplayAdded/Removed/Changed` 是否泄露被隐藏的屏。
 *
 * 结果同时：
 * - 打到 logcat（tag `MoreDisplay_Probe`，`adb logcat -s MoreDisplay_Probe`）；
 * - 显示在界面上（按钮「重新探测」可原地重跑）。
 *
 * 用法：
 *     adb shell am start -n me.ynk.moredisplay.probe/.ProbeActivity
 *     adb logcat -s MoreDisplay_Probe
 */
class ProbeActivity : Activity() {

    companion object {
        const val TAG = "MoreDisplay_Probe"
        private const val MAX_GUESS = 12
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var output: TextView
    private val lines = mutableListOf<String>()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = record("EVENT onDisplayAdded   displayId=$displayId")
        override fun onDisplayRemoved(displayId: Int) = record("EVENT onDisplayRemoved displayId=$displayId")
        override fun onDisplayChanged(displayId: Int) = record("EVENT onDisplayChanged displayId=$displayId")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DisplayManager::class.java)

        output = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        val refresh = Button(this).apply {
            text = "重新探测"
            setOnClickListener { probe() }
        }
        val clear = Button(this).apply {
            text = "清空"
            setOnClickListener {
                lines.clear()
                render()
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(refresh, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(
                ScrollView(this@ProbeActivity).apply { addView(output) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        setContentView(root)

        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        record("listener registered on pid=${Process.myPid()}")
        probe()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        record("onNewIntent -> re-probe")
        probe()
    }

    override fun onDestroy() {
        runCatching { displayManager.unregisterDisplayListener(listener) }
        super.onDestroy()
    }

    /** 一次完整探测：列表 + 猜 id。 */
    private fun probe() {
        val uid = Process.myUid()
        record("========== PROBE uid=$uid pid=${Process.myPid()} ==========")

        val all = displayManager.getDisplays()
        record("getDisplays()             count=${all.size} ids=${all.map { it.displayId }}")
        all.forEach { d ->
            record(
                "  - id=${d.displayId} name='${d.name}' state=${stateName(d.state)} " +
                    "flags=0x${Integer.toHexString(d.flags)} valid=${d.isValid}"
            )
        }

        val presentation = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        record("getDisplays(PRESENTATION) ids=${presentation.map { it.displayId }}")

        val guesses = (0..MAX_GUESS).joinToString(" ") { id ->
            "$id=" + (displayManager.getDisplay(id)?.let { "VISIBLE" } ?: "null")
        }
        record("guess getDisplay(0..$MAX_GUESS): $guesses")
        record("========== PROBE END ==========")
    }

    private fun stateName(state: Int): String = when (state) {
        android.view.Display.STATE_UNKNOWN -> "UNKNOWN"
        android.view.Display.STATE_OFF -> "OFF"
        android.view.Display.STATE_ON -> "ON"
        android.view.Display.STATE_DOZE -> "DOZE"
        android.view.Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        android.view.Display.STATE_VR -> "VR"
        android.view.Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        else -> "state($state)"
    }

    private fun record(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            lines.add(line)
            render()
        }
    }

    private fun render() {
        output.text = lines.joinToString("\n")
    }
}
