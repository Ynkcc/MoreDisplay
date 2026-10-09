package me.ynk.moredisplay.probe

import android.app.Activity
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.view.MotionEvent
import android.view.Surface
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
 * 屏幕可见性 / 录屏替换的「探测程序」—— 一个**普通 App**（不被模块注入、不在 LSPosed 作用域内）。
 *
 * 它只用公开 API 复现目标 App 能做的动作：
 * 1. **可见性**：`DisplayManager.getDisplays()`、`getDisplays(PRESENTATION)`、`getDisplay(0..N)` 猜 id；
 * 2. **事件**：注册 `DisplayListener`，看是否泄露被隐藏的屏；
 * 3. **录屏（需求 B）**：
 *    - 真实路径：`MediaProjectionManager` → 授权 → `MediaProjection.createVirtualDisplay(AUTO_MIRROR)`
 *      → 用 `ImageReader` 收帧；
 *    - 合成路径：直接 `DisplayManager.createVirtualDisplay(AUTO_MIRROR)`（不需要授权弹窗）。
 *
 * 结果同时打到 logcat（tag `MoreDisplay_Probe`）和界面上。
 *
 * 用法（adb）：
 *     adb shell am start -n me.ynk.moredisplay.probe/.ProbeActivity
 *     adb logcat -s MoreDisplay_Probe
 *     adb shell dumpsys display | grep -B2 -A2 mDisplayIdToMirror
 */
class ProbeActivity : Activity() {

    companion object {
        const val TAG = "MoreDisplay_Probe"

        /** 供 adb 无交互驱动：`--es task probe|mirror|mp|release`。 */
        const val EXTRA_TASK = "task"
        const val TASK_PROBE = "probe"
        const val TASK_MIRROR = "mirror"
        const val TASK_MP = "mp"
        const val TASK_OWN = "own"
        const val TASK_RELEASE = "release"
        const val TASK_DUMP = "dump"

        private const val MAX_GUESS = 12
        private const val REQ_PROJECTION = 0x501
        private const val CAPTURE_W = 640
        private const val CAPTURE_H = 360
        private const val CAPTURE_DPI = 160
        private const val SAMPLE_MS = 3500L
    }

    private lateinit var displayManager: DisplayManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var output: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val lines = mutableListOf<String>()

    // --- 录屏探测状态 ---
    private var projection: MediaProjection? = null
    private var captureDisplay: VirtualDisplay? = null
    private var captureReader: ImageReader? = null
    private var captureLabel = ""
    private var frameCount = 0
    private var luminanceSum = 0.0

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = record("EVENT onDisplayAdded   displayId=$displayId")
        override fun onDisplayRemoved(displayId: Int) = record("EVENT onDisplayRemoved displayId=$displayId")
        override fun onDisplayChanged(displayId: Int) = record("EVENT onDisplayChanged displayId=$displayId")
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = record("PROJECTION onStop")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayManager = getSystemService(DisplayManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        output = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        val row1 = row(
            button("清空") { lines.clear(); render() },
            button("重新探测") { probe() }
        )
        val row2 = row(
            button("录屏探测") { startMediaProjectionProbe() },
            button("AUTO_MIRROR") { startSyntheticMirrorProbe() }
        )
        val row3 = row(
            button("释放录屏") { releaseCapture("manual") },
            button("dump mFlags") { dumpFlags() },
            button("清屏日志") { runCatching { Log.i(TAG, "--- marker ---") }; lines.clear(); render() }
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row1)
            addView(row2)
            addView(row3)
            addView(
                ScrollView(this@ProbeActivity).apply { addView(output) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        setContentView(root)

        displayManager.registerDisplayListener(listener, handler)
        record("listener registered on pid=${Process.myPid()}")
        runTask(intent.getStringExtra(EXTRA_TASK) ?: TASK_PROBE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        record("onNewIntent -> task=${intent.getStringExtra(EXTRA_TASK)}")
        runTask(intent.getStringExtra(EXTRA_TASK) ?: TASK_PROBE)
    }

    private fun runTask(task: String) {
        when (task.lowercase()) {
            TASK_PROBE -> probe()
            TASK_MIRROR -> startSyntheticMirrorProbe()
            TASK_MP -> startMediaProjectionProbe()
            TASK_OWN -> startOwnContentProbe()
            TASK_RELEASE -> releaseCapture("task")
            TASK_DUMP -> dumpFlags()
            else -> record("unknown task: $task")
        }
    }

    override fun onDestroy() {
        runCatching { displayManager.unregisterDisplayListener(listener) }
        releaseCapture("onDestroy")
        super.onDestroy()
    }

    /** 需求 C 验收：记录实际到达本窗口的触摸（含 displayId / 坐标）。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        Log.i(TAG, "TOUCH display=${display?.displayId} action=${event.actionMasked} " +
            "x=${event.x} y=${event.y}")
        return super.dispatchTouchEvent(event)
    }

    // ------------------------------------------------------------------ 可见性探测

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

    // ------------------------------------------------------------------ 需求 B：录屏探测

    /** 真实路径：MediaProjection 授权后创建 AUTO_MIRROR 虚拟屏（会被模块改写 mirror 源）。 */
    private fun startMediaProjectionProbe() {
        releaseProjection()
        releaseDisplay()
        CaptureForegroundService.start(this)
        record("PROJECTION 请求授权（需在系统弹窗点「开始录制」）")
        runCatching { startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_PROJECTION) }
            .onFailure { record("PROJECTION 请求失败: $it") }
    }

    /** 合成路径：不走 MediaProjection，直接 AUTO_MIRROR（无需授权弹窗，便于自动化）。 */
    private fun startSyntheticMirrorProbe() {
        record("--- 合成 AUTO_MIRROR 探测（无 MediaProjection）---")
        prepareCapture("SYNTH", DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) { surface ->
            displayManager.createVirtualDisplay(
                "ProbeSynthetic",
                CAPTURE_W, CAPTURE_H, CAPTURE_DPI,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            )
        }
    }

    /**
     * 非录屏对照：`OWN_CONTENT_ONLY`（只显示自己内容，不镜像任何屏）。
     * 这类虚拟屏**不应该**被模块改写 mirror 源。
     */
    private fun startOwnContentProbe() {
        record("--- OWN_CONTENT_ONLY 探测（非镜像屏，应被模块跳过）---")
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        prepareCapture("OWN", flags) { surface ->
            displayManager.createVirtualDisplay(
                "ProbeOwnContent",
                CAPTURE_W, CAPTURE_H, CAPTURE_DPI,
                surface,
                flags
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                record("PROJECTION 被拒绝 (resultCode=$resultCode)")
                CaptureForegroundService.stop(this)
                return
            }
            val mp = runCatching { projectionManager.getMediaProjection(resultCode, data) }
                .onFailure { record("PROJECTION getMediaProjection 失败: $it") }
                .getOrNull() ?: return
            releaseDisplay()
            projection = mp
            // MediaProjection 要求「先注册 callback 再开始采集」，否则 createVirtualDisplay 会抛
            // IllegalStateException: Must register a callback before starting capture.
            mp.registerCallback(projectionCallback, handler)
            record("PROJECTION 已授权 -> 创建 AUTO_MIRROR 虚拟屏")
            prepareCapture("MP", DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) { surface ->
                mp.createVirtualDisplay(
                    "ProbeRecord",
                    CAPTURE_W, CAPTURE_H, CAPTURE_DPI,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
            }
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * 统一入口：给虚拟屏挂一个 ImageReader 收帧，并在一段时间后汇总「是否收到帧 / 画面是否纯黑」。
     *
     * 注意这是**辅助证据**，结论要结合 `dumpsys display` 里的 `mDisplayIdToMirror=`：
     * 镜像到无内容的托管空屏 → 收不到有效帧或纯黑；镜像到 display 0 → 有真实画面。
     */
    private fun prepareCapture(label: String, flags: Int, create: (Surface) -> VirtualDisplay?) {
        // 只释放上一块虚拟屏/reader，**不要**动刚落地的 MediaProjection（否则 callback 会被反注册）。
        releaseDisplay()
        captureLabel = label
        frameCount = 0
        luminanceSum = 0.0

        val reader = ImageReader.newInstance(CAPTURE_W, CAPTURE_H, PixelFormat.RGBA_8888, 2)
        captureReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

        val virtualDisplay = runCatching { create(reader.surface) }
            .onFailure { record("$label 创建虚拟屏失败: $it") }
            .getOrNull()
        if (virtualDisplay == null) {
            record("$label 创建虚拟屏失败（返回 null）")
            return
        }
        captureDisplay = virtualDisplay

        val displayId = virtualDisplay.display?.displayId
        record(
            "$label virtualDisplay=${displayId} ${CAPTURE_W}x${CAPTURE_H} " +
                "flags=0x${Integer.toHexString(flags)}"
        )
        record("$label uid=${Process.myUid()}  —— 现在可以执行 dumpsys display 看 mDisplayIdToMirror=")

        handler.postDelayed({ summarize(label) }, SAMPLE_MS)
    }

    private fun onFrame(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val limit = buffer.limit()
            var sum = 0L
            var samples = 0
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    val index = y * rowStride + x * pixelStride
                    if (index < limit) {
                        sum += buffer.get(index).toInt() and 0xFF
                        samples++
                    }
                    x += 16
                }
                y += 16
            }
            if (samples > 0) {
                frameCount++
                luminanceSum += sum.toDouble() / samples
                if (frameCount == 1 || frameCount % 30 == 0) {
                    record(
                        "$captureLabel FRAME #$frameCount ${image.width}x${image.height} " +
                            "meanByte=%.1f".format(luminanceSum / frameCount)
                    )
                }
            }
        } finally {
            runCatching { image.close() }
        }
    }

    private fun summarize(label: String) {
        val mean = if (frameCount > 0) luminanceSum / frameCount else -1.0
        val verdict = when {
            frameCount == 0 -> "无帧（可能镜像到无合成内容的托管空屏）"
            mean < 20.0 -> "画面很暗 -> 更像镜像到空的托管屏"
            else -> "画面明亮 -> 更像镜像到 display 0"
        }
        record("$label SAMPLING frames=$frameCount meanByte=%.1f verdict=$verdict".format(mean))
    }

    private fun releaseCapture(reason: String) {
        record("CAPTURE releasing ($reason)")
        releaseDisplay()
        releaseProjection()
        CaptureForegroundService.stop(this)
    }

    /** 只释放虚拟屏 + ImageReader（保留 MediaProjection）。 */
    private fun releaseDisplay() {
        if (captureDisplay != null || captureReader != null) {
            captureDisplay?.let { runCatching { it.release() } }
            runCatching { captureReader?.close() }
            record("CAPTURE display released")
        }
        captureDisplay = null
        captureReader = null
        frameCount = 0
        luminanceSum = 0.0
    }

    private fun releaseProjection() {
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
            record("PROJECTION stopped")
        }
        projection = null
    }

    // ------------------------------------------------------------------ mFlags dump

    /**
     * 读取 `dumpsys display` 中每个虚拟屏的 `mFlags`。
     * mFlags 是创建时请求的 `VIRTUAL_DISPLAY_FLAG_*`（经 DMS 归一化），与 Display.getFlags() 是两套位表。
     */
    private fun dumpFlags() {
        val text = runCatching {
            Runtime.getRuntime().exec(arrayOf("dumpsys", "display"))
                .inputStream.bufferedReader().use { it.readText() }
        }.getOrElse { record("DUMP 执行失败: $it"); return }
        if (text.isBlank() || text.contains("Permission Denial")) {
            record("DUMP 权限不足，请先执行: adb shell pm grant me.ynk.moredisplay.probe android.permission.DUMP")
            return
        }
        record("========== MFLAGS DUMP ==========")
        val reUid = Regex("""uniqueId="([^"]+)"""")
        val reFlags = Regex("""\bmFlags=(\d+)\b""")
        var lastUid: String? = null
        var count = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            reUid.find(line)?.let { lastUid = it.groupValues[1] }
            reFlags.find(line)?.let { f ->
                val dec = f.groupValues[1].toInt()
                record(
                    "$lastUid mFlags=$dec (0x${Integer.toHexString(dec)}) " +
                        "${decodeVirtualFlags(dec)}"
                )
                count++
            }
        }
        if (count == 0) record("未找到任何 mFlags 条目")
        record("========== DUMP END ==========")
    }

    /** DisplayManager.VIRTUAL_DISPLAY_FLAG_* 位名（bit0..17，与 aosp17 文档一致）。 */
    private val virtualFlagNames = listOf(
        "PUBLIC", "PRESENTATION", "SECURE", "OWN_CONTENT_ONLY", "AUTO_MIRROR",
        "CAN_SHOW_WITH_INSECURE_KEYGUARD", "SUPPORTS_TOUCH", "ROTATES_WITH_CONTENT",
        "DESTROY_CONTENT_ON_REMOVAL", "SHOULD_SHOW_SYSTEM_DECORATIONS", "TRUSTED",
        "OWN_DISPLAY_GROUP", "ALWAYS_UNLOCKED", "TOUCH_FEEDBACK_DISABLED", "OWN_FOCUS",
        "DEVICE_DISPLAY_GROUP", "STEAL_TOP_FOCUS_DISABLED", "ALLOWS_CONTENT_MODE_SWITCH",
    )

    private fun decodeVirtualFlags(flags: Int): String =
        (0..31).filter { flags and (1 shl it) != 0 }
            .joinToString("|") { virtualFlagNames.getOrNull(it) ?: "bit$it" }
            .ifEmpty { "0" }

    // ------------------------------------------------------------------ 工具

    private fun row(vararg views: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
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
