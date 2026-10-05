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
    private lateinit var sortBar: View
    private lateinit var btnSort: TextView

    private val items = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter

    /** true=可疑优先（黄在前），false=正常优先（绿在前） */
    private var suspiciousFirst = true

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
        sortBar = findViewById(R.id.sort_bar)
        btnSort = findViewById(R.id.btn_sort)
        findViewById<View>(R.id.btn_about).setOnClickListener { showAboutDialog() }

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

        // 排序切换
        btnSort.setOnClickListener {
            suspiciousFirst = !suspiciousFirst
            btnSort.text = if (suspiciousFirst) "可疑优先" else "正常优先"
            sortItems()
            adapter.notifyDataSetChanged()
            listView.setSelection(0)
            appendLog(
                LogEntry(
                    human = "排序已切换：${if (suspiciousFirst) "可疑优先（需判断的在前）" else "正常优先（已知的在前）"}",
                    raw = "sort mode = ${if (suspiciousFirst) "suspicious_first" else "known_first"}"
                )
            )
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

        // 没装 / 没启动 → 弹引导
        maybeShowShizukuGuide(available, granted)
    }

    /** 判断 Shizuku App 是否安装（用于诊断显示） */
    private fun isShizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (t: Throwable) {
        false
    }

    // ==================== Shizuku 引导 ====================

    /** Shizuku 官方下载地址（最新稳定版） */
    private val SHIZUKU_DOWNLOAD_URL =
        "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"

    /** Shizuku 安装 + 授权教程 */
    private val SHIZUKU_TUTORIAL_URL = "https://b23.tv/QwC2wz4"

    /** 本次启动是否已经弹过引导（避免 onResume 反复弹） */
    private var guideShownThisLaunch = false

    /**
     * 检查是否需要弹「引导弹窗」：
     * - 没装 Shizuku → 弹「需要安装」
     * - 装了但服务没运行 → 弹「需要启动」
     * 已授权/正常运行时不弹。
     */
    private fun maybeShowShizukuGuide(available: Boolean, granted: Boolean) {
        if (granted) return
        if (guideShownThisLaunch) return
        guideShownThisLaunch = true

        if (!isShizukuInstalled()) {
            showGuideDialog(
                title = getString(R.string.guide_title_not_installed),
                message = getString(R.string.guide_msg_not_installed),
                showDownload = true
            )
        } else if (!available) {
            showGuideDialog(
                title = getString(R.string.guide_title_not_running),
                message = getString(R.string.guide_msg_not_running),
                showDownload = false
            )
        } else {
            // 装了、服务在跑、但没授权 —— 代码里会自动发起授权请求，让弹窗自然出现
            guideShownThisLaunch = false
        }
    }

    private fun showGuideDialog(title: String, message: String, showDownload: Boolean) {
        val b = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
        if (showDownload) {
            b.setPositiveButton(R.string.guide_btn_download) { _, _ ->
                openUrl(SHIZUKU_DOWNLOAD_URL)
            }
        } else {
            b.setPositiveButton(R.string.guide_btn_ok, null)
        }
        b.setNeutralButton(R.string.guide_btn_tutorial) { _, _ ->
            openUrl(SHIZUKU_TUTORIAL_URL)
        }
        if (!showDownload) {
            // 已装未启动：额外给一个「去启动」入口
            b.setNegativeButton("去启动 Shizuku") { _, _ ->
                launchShizukuApp()
            }
        }
        b.show()
    }

    /** 打开外部链接（APK 下载 / 教程） */
    private fun openUrl(url: String) {
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            // 没有浏览器等：把链接显示出来让用户手动复制
            android.widget.Toast.makeText(
                this,
                getString(R.string.guide_link_failed) + "\n" + url,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 尝试拉起 Shizuku App 主界面（让用户去启动服务） */
    private fun launchShizukuApp() {
        try {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (i != null) startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            // 忽略
        }
    }

    // ==================== 关于 ====================

    private val ABOUT_REPO_URL = "https://github.com/TeaPieyyds/device-check"

    private fun showAboutDialog() {
        val msg = getString(R.string.about_body)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(msg)
            .setPositiveButton(R.string.about_btn_repo) { _, _ ->
                openUrl(ABOUT_REPO_URL)
            }
            .setNeutralButton(R.string.about_btn_license) { _, _ ->
                showLicenseDialog()
            }
            .setNegativeButton(R.string.about_btn_ok, null)
            .show()
    }

    /** 展示完整协议（从 assets/license.txt 读取，纯本地） */
    private fun showLicenseDialog() {
        val text = try {
            assets.open("license.txt").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            "读取协议文件失败：" + t.message
        }
        // 协议很长，用可滚动的 TextView 包在 ScrollView 里
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val tv = android.widget.TextView(this).apply {
            text = text
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("PolyForm Noncommercial License 1.0.0")
            .setView(sv)
            .setPositiveButton(R.string.about_btn_ok, null)
            .show()
    }

    private fun startScan() {
        // 扫描前再次校验 Shizuku：没有权限就直接引导，不进入扫描流程
        val available = ShizukuShell.isAvailable()
        val granted = available && ShizukuShell.hasPermission()
        if (!granted) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.guide_toast_need_shizuku),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            guideShownThisLaunch = false   // 允许用户点扫描时重新弹一次引导
            checkShizukuState()
            return
        }

        // 每次扫描清空
        items.clear()
        adapter.notifyDataSetChanged()
        logEntries.clear()
        renderLog()
        tvStatus.text = "扫描中…"
        progress.visibility = View.VISIBLE
        sortBar.visibility = View.GONE
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
                    // 有结果才显示排序栏，并重置为默认顺序
                    if (items.isNotEmpty()) {
                        suspiciousFirst = true
                        btnSort.text = "可疑优先"
                        sortItems()
                        adapter.notifyDataSetChanged()
                        sortBar.visibility = View.VISIBLE
                    } else {
                        sortBar.visibility = View.GONE
                    }
                    checkShizukuState()
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
        // 边扫边按当前顺序插入，用户能看到实时排序
        sortItems()
        adapter.notifyDataSetChanged()
    }

    /**
     * 按当前排序模式重排 items。
     * 权重：可疑(黄)=0，正常(绿)=1，红=0（未用）。
     * 同一权重内保持原有相对顺序（稳定），避免每次刷新都跳动。
     */
    private fun sortItems() {
        val weight = { l: com.teapieyyds.devicecheck.model.Light ->
            when (l) {
                com.teapieyyds.devicecheck.model.Light.YELLOW -> 0
                com.teapieyyds.devicecheck.model.Light.RED -> 0
                com.teapieyyds.devicecheck.model.Light.GREEN -> 1
            }
        }
        val sorted = items.withIndex().sortedWith(
            compareBy(
                { if (suspiciousFirst) weight(it.value.light) else -weight(it.value.light) },
                { it.index }
            )
        ).map { it.value }
        items.clear()
        items.addAll(sorted)
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