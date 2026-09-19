package me.ynk.moredisplay

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import me.ynk.moredisplay.core.DisplaySpec
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

        private const val ACTION_CREATE = "create"
        private const val ACTION_REMOVE = "remove"
        private const val ACTION_HOLD = "hold"
        private const val ACTION_LIST = "list"
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
        repo.connect().getOrElse { throw IllegalStateException("connect failed: ${it.message}", it) }
        when (action) {
            ACTION_CREATE -> {
                val spec = DisplaySpec(
                    width = intent.getIntExtra(EXTRA_WIDTH, 1080),
                    height = intent.getIntExtra(EXTRA_HEIGHT, 1920),
                    densityDpi = intent.getIntExtra(EXTRA_DPI, 320),
                    name = intent.getStringExtra(EXTRA_NAME) ?: "MoreDisplay",
                    flags = intent.getIntExtra(EXTRA_FLAGS, 0)
                )
                val info = repo.createDisplay(spec).getOrElse {
                    throw IllegalStateException("createDisplay failed: ${it.message}", it)
                }
                "display ${info.displayId} ${info.spec.width}x${info.spec.height}@${info.spec.densityDpi} flags=0x${Integer.toHexString(info.spec.flags)}"
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
                "${it.displayId}(${it.spec.width}x${it.spec.height}@${it.spec.densityDpi})"
            }
            else -> throw IllegalArgumentException("unknown action: $action (create/remove/hold/list)")
        }
    }
}
