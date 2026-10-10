package io.github.ynkcc.moredisplay.daemon

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.dispatch.CapabilityMatrix

/**
 * 运行在 system_server（LSPosed 注入）内的显示器引擎。
 *
 * 与 [ShellDisplayEngine] 的差别只在执行身份：这里以 uid 1000 / 包名 `android` 创建虚拟屏，
 * 因此可以携带 `TRUSTED` 等特权 flag，且归属 system_server，
 * 调用方 App 进程退出也不会带走已创建的屏幕。
 *
 * ## 为什么直接走 `DisplayManagerGlobal` 而不是 `DisplayManager`
 * 引擎在 AMS 构造完成时就被创建，那一刻 DisplayManagerService 还没注册，
 * `DisplayManager` 构造期捕获的 `mGlobal` 会是 null（且该实例已被 Context 缓存），
 * 之后再调用 `createVirtualDisplay` 必然 NPE。
 * `DisplayManagerGlobal.getInstance()` 是静态方法、每次都会重试取服务，因此安全。
 *
 * ## 为什么必须给它挂一个「丢弃型」输出 Surface（实测踩坑）
 * `VirtualDisplayDevice` 的 `mDisplayState` 取决于**有没有输出 Surface**：没有 surface 时
 * `DisplayDeviceInfo.state` 会是 `STATE_OFF`，SurfaceFlinger 不会对该屏做合成，
 * 于是该屏上的 App 拿不到 vsync、窗口一直停在 `mDrawState=NO_SURFACE / mLastHidden=true`，
 * 永远不绘制 —— 表现就是「Activity 起得来（任务栈确实在 display N），但一张画面都没有」，
 * 自然也就录不到任何内容。
 *
 * 因此这里用一个 system_server 内的 `ImageReader` 当**输出接收端**：它让该屏变成
 * `state ON`、被真正合成；回调里立刻 `close()` 掉图像（只当 Sink，不消费内容），
 * 这样缓冲区会立刻回收、不会把合成卡住。托管屏的**内容**始终是它自己的 layer stack。
 */
class SystemDisplayEngine(
    private val systemContext: Context
) : DisplayEngine {

    companion object {
        private const val TAG = "SystemDisplayEngine"
        private const val GLOBAL_CLASS = "android.hardware.display.DisplayManagerGlobal"
        private const val GATE_TAG = "MoreDisplay_RecentsGate"
        private const val ATTACH_INTERVAL_MS = 100L
        private const val ATTACH_MAX_ATTEMPTS = 50
        private const val DETACH_INTERVAL_MS = 100L
        private const val DETACH_MAX_ATTEMPTS = 10
    }

    private val lock = Any()
    private val managed = LinkedHashMap<Int, DisplayInfo>()
    private val handles = LinkedHashMap<Int, VirtualDisplay>()

    /** displayId → 该屏的输出接收端（丢弃型 sink）。 */
    private val sinks = LinkedHashMap<Int, ImageReader>()

    private val sinkThread = HandlerThread("MoreDisplay-display-sink").apply { start() }
    private val sinkHandler = Handler(sinkThread.looper)

    override val capabilities = CapabilityMatrix.forPrivilege(Privilege.LSPOSED)

    override fun createDisplay(spec: DisplaySpec): DisplayInfo {
        if (!capabilities.supportsFlags(spec.flags)) {
            throw SecurityException("unsupported flags 0x${Integer.toHexString(spec.flags)}")
        }
        val flags = CapabilityMatrix.normalizeFlagCombination(
            CapabilityMatrix.flagsForSdk(spec.flags)
        )

        val global = Class.forName(GLOBAL_CLASS).getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("DisplayManagerGlobal unavailable (is the display service up?)")
        val create = global.javaClass.methods.firstOrNull {
            it.name == "createVirtualDisplay" &&
                it.parameterTypes.firstOrNull() == Context::class.java &&
                it.returnType == VirtualDisplay::class.java
        } ?: throw NoSuchMethodException("createVirtualDisplay(Context, ...): VirtualDisplay not found")

        val sink = newSink(spec.width, spec.height)
        val config = VirtualDisplayConfig.Builder(spec.name, spec.width, spec.height, spec.densityDpi)
            .setFlags(flags)
            .setSurface(sink.surface)
            .build()
        val vd = runCatching { create.invoke(global, systemContext, null, config, null, null) as? VirtualDisplay }
            .getOrElse {
                runCatching { sink.reader.close() }
                throw it
            } ?: run {
            runCatching { sink.reader.close() }
            throw IllegalStateException("createVirtualDisplay returned null for $spec")
        }

        val info = DisplayInfo(vd.display.displayId, spec)
        synchronized(lock) {
            managed[info.displayId] = info
            handles[info.displayId] = vd
            sinks[info.displayId] = sink.reader
        }
        // 最近任务门禁：DisplayContent 由 WMS 异步创建，轮询注入（失败不建屏失败，仅记日志）。
        scheduleAttachRecentsGate(info.displayId)
        Log.i(
            TAG,
            "created displayId=${info.displayId} ${spec.width}x${spec.height}@${spec.densityDpi} " +
                "flags=0x${Integer.toHexString(flags)} owner=${systemContext.packageName} (with output sink)"
        )
        return info
    }

    /**
     * 轮询等待 WMS 为 displayId 建好 `DisplayContent`，然后注入 RecentsGate 门禁。
     * 每 100ms 一次，最多 50 次（5s）；成功/最终失败都只打一条日志，绝不抛出。
     */
    private fun scheduleAttachRecentsGate(displayId: Int, attempt: Int = 0) {
        if (attempt == 0) {
            Log.i(GATE_TAG, "attach scheduled displayId=$displayId (gate armed)")
        }
        sinkHandler.postDelayed({
            val stillManaged = synchronized(lock) { managed.containsKey(displayId) }
            if (!stillManaged) {
                Log.i(GATE_TAG, "attach aborted displayId=$displayId (display removed)")
                return@postDelayed
            }
            when {
                RecentsGate.attach(displayId) -> Unit
                attempt >= ATTACH_MAX_ATTEMPTS - 1 ->
                    Log.e(GATE_TAG, "attach FAILED displayId=$displayId after $ATTACH_MAX_ATTEMPTS attempts")
                else -> scheduleAttachRecentsGate(displayId, attempt + 1)
            }
        }, ATTACH_INTERVAL_MS)
    }

    private fun scheduleDetachRecentsGate(displayId: Int, attempt: Int = 0) {
        sinkHandler.postDelayed({
            when {
                RecentsGate.detach(displayId) -> Unit
                attempt >= DETACH_MAX_ATTEMPTS - 1 ->
                    Log.e(GATE_TAG, "detach FAILED displayId=$displayId (display is being removed anyway)")
                else -> scheduleDetachRecentsGate(displayId, attempt + 1)
            }
        }, DETACH_INTERVAL_MS)
    }

    /**
     * 建一个「只接收、立刻丢弃」的输出 Surface，让该虚拟屏被 SurfaceFlinger 真正合成（state ON）。
     * 尺寸按屏分辨率给，实际 buffer 尺寸由生产端（SurfaceFlinger）决定。
     */
    private fun newSink(width: Int, height: Int): Sink {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            runCatching { r.acquireLatestImage()?.close() }
        }, sinkHandler)
        return Sink(reader, reader.surface)
    }

    private class Sink(val reader: ImageReader, val surface: Surface)

    override fun holdDisplay(displayId: Int): DisplayInfo? = synchronized(lock) { managed[displayId] }

    override fun removeDisplay(displayId: Int) {
        val vd: VirtualDisplay
        val sink: ImageReader?
        synchronized(lock) {
            managed.remove(displayId) ?: throw IllegalArgumentException("display $displayId not managed")
            vd = handles.remove(displayId)
                ?: throw IllegalStateException("display $displayId has no VirtualDisplay handle")
            sink = sinks.remove(displayId)
        }
        // 先拔门禁再释放屏幕：此刻 DisplayContent 通常还在，同步拔除；
        // 若 WMS 侧对象竞态导致失败，再用短重试兜底（release 后 DisplayContent 会销毁，属正常）。
        if (!RecentsGate.detach(displayId)) {
            scheduleDetachRecentsGate(displayId)
        }
        runCatching { vd.release() }
        runCatching { sink?.close() }
        Log.i(TAG, "released displayId=$displayId")
    }

    override fun listDisplays(): List<DisplayInfo> = synchronized(lock) { managed.values.toList() }
}
