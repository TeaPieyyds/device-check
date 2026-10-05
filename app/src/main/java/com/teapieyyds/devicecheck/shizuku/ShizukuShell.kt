package com.teapieyyds.devicecheck.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 命令执行封装。
 *
 * Shizuku.newProcess 在 api 中为 private，这里通过反射调用并开放访问，
 * 这是社区通行做法。所有 exec 调用都应在工作线程执行。
 */
object ShizukuShell {

    const val PERMISSION_REQUEST_CODE = 1001

    /** Shizuku 服务是否在运行 */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** 是否已获得本应用的授权 */
    fun hasPermission(): Boolean = try {
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 当前权限状态码：用于诊断 */
    fun permissionStatusCode(): Int = try {
        Shizuku.checkSelfPermission()
    } catch (t: Throwable) {
        -999
    }

    /** 诊断信息：返回一段人话描述当前状态 */
    fun diagnose(installed: Boolean): String {
        val sb = StringBuilder()
        sb.append("Shizuku 诊断\n")
        sb.append("· Shizuku 已安装：").append(installed).append('\n')
        val ping = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
        sb.append("· 服务可达(pingBinder)：").append(ping).append('\n')
        val code = permissionStatusCode()
        sb.append("· 权限状态码：").append(code).append('\n')
        sb.append("  （0=已授权，-1=被拒绝，-999=读取异常）\n")
        sb.append("· 本应用 uid：").append(android.os.Process.myUid()).append('\n')
        return sb.toString()
    }

    /** 请求授权 */
    fun requestPermission() {
        try {
            if (isAvailable() && !hasPermission()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (t: Throwable) {
            // 交由监听器处理
        }
    }

    /**
     * 执行 shell 命令，返回标准输出（失败时返回标准错误）。
     * 必须在工作线程调用。
     */
    fun exec(command: String): String {
        val process = newProcess(arrayOf("sh", "-c", command))
            ?: return "ERROR: shizuku process is null"
        val out = readAll(process.inputStream)
        val err = readAll(process.errorStream)
        process.waitFor()
        return if (out.isNotEmpty()) out else err
    }

    /** 打开卸载页的结果 */
    sealed class UninstallResult {
        object Success : UninstallResult()
        data class Failure(val reason: String) : UninstallResult()
    }

    /**
     * 以 shell（adb）身份打开指定包的系统卸载页。
     *
     * 为什么绕这一圈？
     *  - 普通 App 直接发 ACTION_DELETE，在 OPPO ColorOS / vivo OriginOS 等
     *    ROM 上会被拦截：Activity 被创建后立即 finish，表现为「跳一下又回来」。
     *  - 而 shell 拥有 android.permission.DELETE_PACKAGES，用 `am start` 发起
     *    同样的 Intent 就不会被拦，系统卸载页能正常打开。
     *
     * 安全性：
     *  - 只是**打开页面**，不会直接卸载 —— 最终是否卸载由用户在系统页面上决定
     *  - 包名做了白名单校验，防止命令注入
     *
     * 必须在工作线程调用。
     */
    fun openUninstallPage(pkg: String): UninstallResult {
        // 防注入：包名只允许字母数字点下划线
        if (!pkg.matches(Regex("^[A-Za-z0-9_.]+$"))) {
            return UninstallResult.Failure("包名格式异常，已拒绝执行")
        }
        val cmd = "am start -a android.intent.action.DELETE -d package:$pkg 2>&1"
        val out = exec(cmd).trim()

        // am start 成功：输出含 "Starting: Intent" 或 "Activity:"
        // 失败：含 "Error:" / "Permission Denial" / "SecurityException"
        val looksOk = out.contains("Starting:", ignoreCase = true) ||
            out.contains("Activity:", ignoreCase = true)
        val looksBad = out.contains("Permission Denial", ignoreCase = true) ||
            out.contains("SecurityException", ignoreCase = true) ||
            out.contains("Error type", ignoreCase = true) ||
            out.contains("does not exist", ignoreCase = true)

        return when {
            looksOk && !looksBad -> UninstallResult.Success
            looksBad -> UninstallResult.Failure(friendlyReason(out))
            // 输出异常但没明确报错：也算成功（可能是不同 ROM 的输出格式）
            else -> UninstallResult.Success
        }
    }

    /**
     * 备用：用 shell 身份打开「应用信息页」。
     * 当卸载页打不开时（或用户主动选择）使用。
     */
    fun openAppDetailsPage(pkg: String): UninstallResult {
        if (!pkg.matches(Regex("^[A-Za-z0-9_.]+$"))) {
            return UninstallResult.Failure("包名格式异常，已拒绝执行")
        }
        val cmd = "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$pkg 2>&1"
        val out = exec(cmd).trim()
        return if (out.contains("Error", ignoreCase = true) ||
            out.contains("Exception", ignoreCase = true)
        ) {
            UninstallResult.Failure(friendlyReason(out))
        } else {
            UninstallResult.Success
        }
    }

    /** 把 shell 报错翻译成人话 */
    private fun friendlyReason(raw: String): String = when {
        raw.contains("Permission Denial") -> "权限不足，请确认 Shizuku 已授权"
        raw.contains("does not exist") || raw.contains("not installed") ->
            "找不到该应用（可能已被卸载）"
        raw.contains("Exception") -> "系统拒绝打开该页面"
        else -> raw.ifBlank { "未知错误" }
    }

    /** 反射调用 Shizuku.newProcess */
    private fun newProcess(cmd: Array<String>): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? Process
        } catch (t: Throwable) {
            null
        }
    }

    private fun readAll(stream: java.io.InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(stream)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                sb.append(line).append('\n')
                line = reader.readLine()
            }
        }
        return sb.toString()
    }
}