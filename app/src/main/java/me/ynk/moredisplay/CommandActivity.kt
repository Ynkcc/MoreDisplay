package me.ynk.moredisplay

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import me.ynk.moredisplay.core.DisplayPolicy
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.core.Visibility
import java.util.concurrent.Executors

class CommandActivity : Activity() {

    companion object {
        private const val TAG = "MoreDisplay_Cmd"

        const val EXTRA_ACTION = "action"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DPI = "dpi"
        const val EXTRA_NAME = "name"
        const val EXTRA_DISPLAY_ID = "display_id"
        const val EXTRA_FLAGS = "flags"

        const val EXTRA_UID = "uid"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DISPLAY_IDS = "display_ids"

        /** create 时指定守护通道：lsposed / shell_shizuku（缺省取最高优先级已连接通道）。 */
        const val EXTRA_PRIVILEGE = "privilege"

        /** 需求 B：录屏时把 mirror 源改写到该 displayId。（需求 C 预留 operated_display_id） */
        const val EXTRA_RECORD_DISPLAY_ID = "record_display_id"
        const val EXTRA_OPERATED_DISPLAY_ID = "operated_display_id"

        private const val ACTION_CREATE = "create"
        private const val ACTION_REMOVE = "remove"
        private const val ACTION_HOLD = "hold"
        private const val ACTION_LIST = "list"
        private const val ACTION_POLICY_SET = "policy-set"
        private const val ACTION_POLICY_LIST = "policy-list"
        private const val ACTION_POLICY_REMOVE = "policy-remove"
        private const val ACTION_DISPLAYS_FOR_UID = "displays-for-uid"

        /** 请求 Shizuku 授权（弹出系统对话框，用户点允许后日志输出结果）。 */
        private const val ACTION_SHIZUKU_PERMIT = "shizuku-permit"
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "md-cmd").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(EXTRA_ACTION)?.lowercase() ?: ACTION_CREATE
        executor.execute {
            val outcome: kotlin.Pair<Boolean, String> = runCatching { runBlocking { execute(action) } }
                .fold(
                    { true to it },
                    { false to "${it::class.java.simpleName}: ${it.message}" }
                )
            runOnUiThread {
                val (ok, msg) = outcome
                if (ok) {
                    Log.i(TAG, "[$action] ok: $msg")
                    Toast.makeText(applicationContext, "[$action] ok: $msg", Toast.LENGTH_LONG).show()
                } else {
                    Log.e(TAG, "[$action] failed: $msg")
                    Toast.makeText(applicationContext, "[$action] failed: $msg", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    private fun execute(action: String): String = runBlocking {
        val repo = App.displays()
        // shizuku-permit 用于获取权限本身，不能依赖 connect（connect 需要权限），
        // 否则在权限未授予时永远弹不出授权对话框（鸡生蛋问题）。
        if (action != ACTION_SHIZUKU_PERMIT) {
            repo.connect().getOrElse { throw IllegalStateException("connect failed: ${it.message}", it) }
        }
        when (action) {
            ACTION_CREATE -> {
                val spec = DisplaySpec(
                    width = intent.getIntExtra(EXTRA_WIDTH, 1080),
                    height = intent.getIntExtra(EXTRA_HEIGHT, 1920),
                    densityDpi = intent.getIntExtra(EXTRA_DPI, 320),
                    name = intent.getStringExtra(EXTRA_NAME) ?: "MoreDisplay",
                    flags = intent.getIntExtra(EXTRA_FLAGS, 0)
                )
                val privilege = intent.getStringExtra(EXTRA_PRIVILEGE)?.let { raw ->
                    me.ynk.moredisplay.core.Privilege.entries.firstOrNull {
                        it.name.equals(raw, ignoreCase = true)
                    } ?: throw IllegalArgumentException(
                        "unknown privilege '$raw' (candidates: lsposed/root/shell_shizuku)"
                    )
                }
                val info = repo.createDisplay(spec, privilege).getOrElse {
                    throw IllegalStateException("createDisplay failed: ${it.message}", it)
                }
                "display ${info.displayId} ${info.spec.width}x${info.spec.height}@${info.spec.densityDpi} " +
                    "flags=0x${Integer.toHexString(info.spec.flags)} via ${repo.displayOrigin(info.displayId)}"
            }
            ACTION_REMOVE -> {
                val id = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                require(id >= 0) { "missing --ei display_id" }
                repo.removeDisplay(id).getOrElse {
                    throw IllegalStateException("removeDisplay $id failed: ${it.message}", it)
                }
                "display $id removed"
            }
            ACTION_HOLD -> {
                val id = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                require(id >= 0) { "missing --ei display_id" }
                val info = repo.holdDisplay(id).getOrElse {
                    throw IllegalStateException("holdDisplay $id failed: ${it.message}", it)
                }
                "display ${info.displayId} held"
            }
            ACTION_LIST -> repo.listDisplays().getOrElse {
                throw IllegalStateException("listDisplays failed: ${it.message}", it)
            }.joinToString(prefix = "displays: ") {
                val via = repo.displayOrigin(it.displayId)?.let { p -> "@$p" } ?: ""
                "${it.displayId}(${it.spec.width}x${it.spec.height}@${it.spec.densityDpi}$via)"
            }

            ACTION_POLICY_SET -> {
                val uid = resolveUid()
                val mode = Visibility.fromName(intent.getStringExtra(EXTRA_MODE))
                val ids = intent.getIntArrayExtra(EXTRA_DISPLAY_IDS)?.toSet() ?: emptySet()
                if (mode != Visibility.ALL && ids.isEmpty()) {
                    throw IllegalArgumentException("mode=$mode 时必须带 --eia display_ids 1,2")
                }
                val policy = DisplayPolicy(
                    uid = uid,
                    packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE),
                    visibility = mode,
                    displayIds = ids,
                    operatedDisplayId = intent.getIntExtra(EXTRA_OPERATED_DISPLAY_ID, -1).takeIf { it >= 0 },
                    recordDisplayId = intent.getIntExtra(EXTRA_RECORD_DISPLAY_ID, -1).takeIf { it >= 0 }
                )
                val stored = repo.setDisplayPolicy(policy).getOrElse {
                    throw IllegalStateException("setDisplayPolicy failed: ${it.message}", it)
                }
                "policy stored: ${stored.describe()}"
            }
            ACTION_POLICY_LIST -> {
                val policies = repo.listDisplayPolicies().getOrElse {
                    throw IllegalStateException("listDisplayPolicies failed: ${it.message}", it)
                }
                if (policies.isEmpty()) {
                    "policies: <empty>"
                } else {
                    policies.joinToString(prefix = "policies: ") { it.describe() }
                }
            }

            ACTION_POLICY_REMOVE -> {
                val uid = resolveUid()
                repo.removeDisplayPolicy(uid).getOrElse {
                    throw IllegalStateException("removeDisplayPolicy failed: ${it.message}", it)
                }
                "policy removed uid=$uid"
            }
            ACTION_DISPLAYS_FOR_UID -> {
                val uid = resolveUid()
                val ids = repo.displaysForUid(uid).getOrElse {
                    throw IllegalStateException("displaysForUid failed: ${it.message}", it)
                }
                "uid=$uid visible displays: $ids"
            }
            ACTION_SHIZUKU_PERMIT -> {
                if (!rikka.shizuku.Shizuku.pingBinder()) {
                    // server 未运行或本进程尚未收到 binder；不给提示会直接抛
                    // "binder haven't been received"，难以定位。
                    "shizuku server not running (start via adb: libshizuku.so, or from the Shizuku app)"
                } else if (rikka.shizuku.Shizuku.getVersion() < 11) {
                    "shizuku server too old, upgrade shizuku"
                } else if (rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    "shizuku already granted"
                } else {
                    val result = kotlinx.coroutines.CompletableDeferred<Int>()
                    val listener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                            if (requestCode == 100) result.complete(grantResult)
                        }
                    }
                    rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
                    try {
                        rikka.shizuku.Shizuku.requestPermission(100)
                        val granted = kotlinx.coroutines.withTimeoutOrNull(30_000) { result.await() }
                        when {
                            granted == null -> "shizuku permission request timed out (dialog dismissed?)"
                            granted == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                "shizuku granted"
                            else -> "shizuku denied: $granted"
                        }
                    } finally {
                        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener)
                    }
                }
            }
            else -> throw IllegalArgumentException(
                "unknown action: $action (create/remove/hold/list/policy-set/policy-list/" +
                    "policy-remove/displays-for-uid/shizuku-permit)"
            )
        }
    }

    /** 支持 `--ei uid N` 直接指定，或 `--es target_package me.ynk.moredisplay.probe` 由包名解析。 */
    private fun resolveUid(): Int {
        val explicit = intent.getIntExtra(EXTRA_UID, -1)
        if (explicit >= 0) return explicit
        val pkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?: throw IllegalArgumentException("missing --ei uid or --es target_package")
        return runCatching { packageManager.getApplicationInfo(pkg, 0).uid }
            .getOrElse { throw IllegalArgumentException("package $pkg not installed: ${it.message}") }
    }
}
