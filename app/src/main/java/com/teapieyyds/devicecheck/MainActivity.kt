package com.teapieyyds.devicecheck

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.teapieyyds.devicecheck.model.AppItem
import com.teapieyyds.devicecheck.model.LogEntry
import com.teapieyyds.devicecheck.shizuku.ShizukuShell
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * 主界面：扫描按钮 + 结果列表 + 底部日志（人话/原样 双模式）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var btnScan: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnLogMode: TextView
    private lateinit var progress: View
    private lateinit var shizukuTip: TextView

    private val items = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter

    /** 日志全量（双模式同时保存） */
    private val logEntries = mutableListOf<LogEntry>()

    /** true=人话模式，false=原样模式 */
    private var humanMode = true

    /** Shizuku 权限监听器 */
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuShell.PERMISSION_REQUEST_CODE) {
                runOnUiThread { checkShizukuState() }
            }
        }

    private val binderListener =
        Shizuku.OnBinderReceivedListener {
            runOnUiThread { checkShizukuState() }
        }

    /** 扫描用的后台线程 */
    private val scanThread = Executors.newSingleThreadExecutor()

    private var engine: ScanEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.list)
        btnScan = findViewById(R.id.btn_scan)
        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)
        logScroll = findViewById(R.id.log_scroll)
        btnLogMode = findViewById(R.id.btn_log_mode)
        progress = findViewById(R.id.progress)
        shizukuTip = findViewById(R.id.tv_shizuku_tip)

        adapter = AppAdapter(
            context = this,
            items = items,
            onLog = { entry -> appendLog(entry) },
            onRemove = { item -> removeItem(item) }
        )
        listView.adapter = adapter

        btnScan.setOnClickListener { startScan() }

        // 日志模式切换
        btnLogMode.setOnClickListener {
            humanMode = !humanMode
            btnLogMode.text = if (humanMode) "人话" else "原样"
            renderLog()
        }

        // 注册 Shizuku 监听器
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListener(binderListener)
        } catch (t: Throwable) {
            // Shizuku 未安装/未运行时可能抛异常，忽略
        }

        checkShizukuState()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时重新检查（用户可能刚从 Shizuku 里授权回来）
        checkShizukuState()
    }

    private fun checkShizukuState() {
        val available = ShizukuShell.isAvailable()
        val granted = available && ShizukuShell.hasPermission()

        // 把诊断信息打到日志区，方便定位
        val diag = ShizukuShell.diagnose(isShizukuInstalled())
        appendLog(
            LogEntry(
                human = "Shizuku 状态：服务${if (available) "在线" else "离线"} / 授权${if (granted) "已获得" else "未获得"}",
                raw = diag
            )
        )

        when {
            granted -> {
                shizukuTip.visibility = View.GONE
                btnScan.isEnabled = true
            }
            available -> {
                shizukuTip.text = getString(R.string.shizuku_need_permission)
                shizukuTip.visibility = View.VISIBLE
                btnScan.isEnabled = true
                ShizukuShell.requestPermission()
            }
            else -> {
                shizukuTip.text = getString(R.string.shizuku_not_running)
                shizukuTip.visibility = View.VISIBLE
                btnScan.isEnabled = true
            }
        }
    }

    /** 判断 Shizuku App 是否安装（用于诊断显示） */
    private fun isShizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (t: Throwable) {
        false
    }

    private fun startScan() {
        // 每次扫描清空
        items.clear()
        adapter.notifyDataSetChanged()
        logEntries.clear()
        renderLog()
        tvStatus.text = "扫描中…"
        progress.visibility = View.VISIBLE
        btnScan.isEnabled = false

        engine?.shutdown()
        engine = ScanEngine(
            context = this,
            onLog = { entry -> runOnUiThread { appendLog(entry) } },
            onItem = { item -> runOnUiThread { appendItem(item) } },
            onStatus = { s -> runOnUiThread { tvStatus.text = s } }
        )

        scanThread.execute {
            try {
                engine?.scan()
            } catch (t: Throwable) {
                runOnUiThread {
                    appendLog(LogEntry("扫描出错：${t.message}", t.toString()))
                    tvStatus.text = "扫描出错"
                }
            } finally {
                runOnUiThread {
                    progress.visibility = View.GONE
                    btnScan.isEnabled = true
                    checkShizukuState()
                    if (tvStatus.text == "扫描完成" || tvStatus.text == "扫描出错") {
                        // 保持原状态
                    }
                }
            }
        }
    }

    /** 追加日志：双模式都记录，按当前模式渲染 */
    private fun appendLog(entry: LogEntry) {
        logEntries.add(entry)
        // 限制 200 条
        while (logEntries.size > 200) {
            logEntries.removeAt(0)
        }
        renderLog()
    }

    private fun renderLog() {
        val sb = StringBuilder()
        for (e in logEntries) {
            sb.append(if (humanMode) e.human else e.raw).append('\n')
        }
        tvLog.text = sb.toString()
        // 自动滚到底
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendItem(item: AppItem) {
        items.add(item)
        adapter.notifyDataSetChanged()
    }

    private fun removeItem(item: AppItem) {
        items.remove(item)
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.shutdown()
        scanThread.shutdownNow()
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderListener)
        } catch (t: Throwable) {
            // 忽略
        }
    }
}