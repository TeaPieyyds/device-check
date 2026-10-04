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