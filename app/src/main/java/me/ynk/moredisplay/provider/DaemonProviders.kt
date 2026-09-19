package me.ynk.moredisplay.provider

import android.util.Log
import me.ynk.moredisplay.core.Privilege
import me.ynk.moredisplay.core.Transport
import me.ynk.moredisplay.dispatch.DaemonProvider

class LsposedDaemonProvider : DaemonProvider {
    override val privilege = Privilege.LSPOSED
    override val priority = 100

    override fun isAvailable(): Boolean =
        me.ynk.moredisplay.xposed.LsposedBridge.isInjected

    override suspend fun startDaemon(): Result<Unit> = Result.success(Unit)

    override suspend fun stopDaemon(): Result<Unit> = Result.success(Unit)

    override suspend fun connectTransport(): Result<Transport> = runCatching {
        me.ynk.moredisplay.transport.BinderTransport.connect()
    }
}

class ShizukuDaemonProvider : DaemonProvider {
    override val privilege = Privilege.SHELL_SHIZUKU
    override val priority = 50

    override fun isAvailable(): Boolean = TODO("shizuku binder check: rikka.shizuku api")

    override suspend fun startDaemon(): Result<Unit> = TODO("push kotlin daemon dex/jar via shizuku UserService and exec")

    override suspend fun stopDaemon(): Result<Unit> = TODO("kill shell daemon process")

    override suspend fun connectTransport(): Result<Transport> = TODO("connect local abstract socket of shell daemon")
}

class RootDaemonProvider : DaemonProvider {
    override val privilege = Privilege.ROOT
    override val priority = 10

    override fun isAvailable(): Boolean = TODO("root availability check (su -c true)")

    override suspend fun startDaemon(): Result<Unit> = TODO("adb/root: app_process launch kotlin daemon jar")

    override suspend fun stopDaemon(): Result<Unit> = TODO("kill root daemon process")

    override suspend fun connectTransport(): Result<Transport> = TODO("connect local abstract socket of root daemon")
}
