package com.teapieyyds.devicecheck

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.teapieyyds.devicecheck.model.AppItem
import com.teapieyyds.devicecheck.model.Light
import com.teapieyyds.devicecheck.model.LogEntry
import com.teapieyyds.devicecheck.shizuku.ShizukuShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 扫描引擎。
 *
 * 多线程执行各项检查，每项完成后通过回调实时上报日志。
 * 所有回调都在调用线程（后台线程池）触发，由调用方切主线程。
 */
class ScanEngine(
    private val context: Context,
    private val onLog: (LogEntry) -> Unit,
    private val onItem: (AppItem) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private val pool = Executors.newFixedThreadPool(4)

    /** 需要在分享时附带的危险权限 */
    private val dangerousPermissions = setOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.CAMERA"
    )

    /**
     * 启动扫描。阻塞直到全部完成（本方法应在后台线程调用）。
     */
    fun scan() {
        Whitelist.load(context)
        ThreatDB.load(context)
        onStatus("正在检查权限…")

        if (!ShizukuShell.isAvailable()) {
            onLog(LogEntry("Shizuku 未运行，无法获取完整信息", "Shizuku.pingBinder() == false"))
            onStatus("Shizuku 未运行")
            return
        }
        if (!ShizukuShell.hasPermission()) {
            onLog(LogEntry("尚未授权 Shizuku，正在申请…", "Shizuku.requestPermission()"))
            ShizukuShell.requestPermission()
            onStatus("等待 Shizuku 授权")
            return
        }

        onStatus("扫描中…")

        // 先同步读取设备管理员列表（后续应用判定要用），再并行其余
        val activeAdmins = runCatching { scanDeviceAdmin() }.getOrDefault(emptySet())

        val latch = CountDownLatch(2)

        // 并行：应用列表
        pool.execute {
            try {
                scanPackages(activeAdmins)
            } finally {
                latch.countDown()
            }
        }
        // 并行：无障碍 + 自启
        pool.execute {
            try {
                scanAccessibilityAndBoot()
            } finally {
                latch.countDown()
            }
        }

        latch.await(60, TimeUnit.SECONDS)
        onStatus("扫描完成")
        onLog(LogEntry("全部检查完成", "# scan finished"))
    }

    private fun scanPackages(activeAdmins: Set<String>) {
        onLog(LogEntry("正在读取应用列表…", "pm list packages -f --user 0"))
        val output = ShizukuShell.exec("pm list packages -f --user 0")
        val lines = output.lineSequence().filter { it.startsWith("package:") }.toList()
        onLog(LogEntry("找到 ${lines.size} 个应用，开始逐一识别", "packages count = ${lines.size}"))

        val pm = context.packageManager

        for (line in lines) {
            // 格式：package:/path/base.apk=com.example.app
            val eq = line.lastIndexOf('=')
            if (eq < 0) continue
            val pkg = line.substring(eq + 1).trim()
            if (pkg.isEmpty()) continue

            val label = runCatching {
                val ai: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString()
            }.getOrNull()

            // 是否为系统分区预装应用（出厂自带）
            val isSystemApp = runCatching {
                val ai: ApplicationInfo = pm.getApplicationInfo(pkg, 0)
                (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            }.getOrDefault(false)

            val launchable = runCatching {
                pm.getLaunchIntentForPackage(pkg) != null
            }.getOrDefault(false)

            val installer = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    pm.getInstallSourceInfo(pkg).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(pkg)
                }
            }.getOrNull()

            val threat = ThreatDB.match(pkg)
            val hit = Whitelist.match(pkg)
            val isAdmin = pkg in activeAdmins
            val light: Light
            val note: String?
            if (threat != null) {
                // 0. 威胁库命中：最高优先级，直接判红
                light = Light.RED
                note = ThreatDB.describe(threat)
            } else if (isAdmin) {
                // 0.5 活跃设备管理员：高风险，值得警惕
                light = Light.RED
                note = "⚠️ 已激活为设备管理员 · 可能阻止卸载，请确认是否认识"
            } else if (hit != null) {
                // 1. 白名单命中：我认识它
                light = Light.GREEN
                val hitName = hit.first
                val hitNote = hit.second
                note = if (hitName.isNotEmpty()) "$hitName · $hitNote" else hitNote
            } else if (isSystemApp) {
                // 2. 系统分区预装：出厂自带，不需要用户判断
                light = Light.GREEN
                note = "系统预装应用 · 出厂自带，正常"
            } else {
                // 3. 其余：我不认识，交给用户判断
                light = Light.YELLOW
                note = null
            }

            onItem(
                AppItem(
                    pkg = pkg,
                    label = label,
                    note = note,
                    light = light,
                    launchable = launchable,
                    installer = installer,
                    isActiveAdmin = isAdmin
                )
            )
        }

        onLog(
            LogEntry(
                "应用列表读取完毕",
                "pm list packages done, ${lines.size} packages"
            )
        )
    }

    private fun scanDeviceAdmin(): Set<String> {
        onLog(LogEntry("正在检查设备管理员…", "dumpsys device_policy"))
        val output = ShizukuShell.exec("dumpsys device_policy")
        val admins = mutableSetOf<String>()
        var counting = false
        for (raw in output.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("Enabled Device Admins")) {
                counting = true
                continue
            }
            if (counting) {
                if (l.startsWith("com.") || l.startsWith("org.")) {
                    // 形如 com.demo.admintest/.TestDeviceAdminReceiver
                    // 取「/」前的包名部分，与应用列表的纯包名对齐
                    val comp = l.substringBefore(':')
                    admins.add(comp.substringBefore('/'))
                } else if (l.startsWith("Enabled") || l.isEmpty()) {
                    counting = false
                }
            }
        }
        onLog(
            LogEntry(
                "设备管理员共 ${admins.size} 个：${admins.joinToString("、").ifEmpty { "无" }}",
                output.take(1500).trim()
            )
        )
        return admins
    }

    private fun scanAccessibilityAndBoot() {
        // 无障碍
        onLog(LogEntry("正在检查无障碍服务…", "settings get secure enabled_accessibility_services"))
        val acc = ShizukuShell.exec(
            "settings get secure enabled_accessibility_services"
        ).trim()
        val accList = if (acc == "null" || acc.isEmpty()) {
            "无"
        } else {
            acc.split(":").joinToString("、") { it.substringBefore('/') }
        }
        onLog(
            LogEntry(
                "已启用的无障碍服务：$accList",
                acc
            )
        )

        // 开机自启
        onLog(LogEntry("正在检查开机自启…", "dumpsys package | grep BOOT_COMPLETED"))
        val boot = ShizukuShell.exec(
            "dumpsys package | grep BOOT_COMPLETED"
        ).trim()
        val bootCount = boot.lineSequence().count { it.contains("BOOT_COMPLETED") }
        onLog(
            LogEntry(
                if (bootCount == 0) "未发现第三方开机自启" else "发现 $bootCount 处开机自启声明",
                boot.take(1500)
            )
        )
    }

    /** 释放线程池 */
    fun shutdown() {
        runCatching { pool.shutdownNow() }
    }
}
