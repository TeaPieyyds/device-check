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

        val latch = CountDownLatch(3)

        // 并行：应用列表
        pool.execute {
            try {
                scanPackages()
            } finally {
                latch.countDown()
            }
        }
        // 并行：设备管理员
        pool.execute {
            try {
                scanDeviceAdmin()
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

    private fun scanPackages() {
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

            val hit = Whitelist.match(pkg)
            val light: Light
            val note: String?
            if (hit != null) {
                light = Light.GREEN
                val hitName = hit.first
                val hitNote = hit.second
                note = if (hitName.isNotEmpty()) "$hitName · $hitNote" else hitNote
            } else {
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
                    installer = installer
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

    private fun scanDeviceAdmin() {
        onLog(LogEntry("正在检查设备管理员…", "dumpsys device_policy"))
        val output = ShizukuShell.exec("dumpsys device_policy")
        val admins = mutableListOf<String>()
        var counting = false
        for (raw in output.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("Enabled Device Admins")) {
                counting = true
                continue
            }
            if (counting) {
                if (l.startsWith("com.") || l.startsWith("org.")) {
                    admins.add(l.substringBefore(':'))
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