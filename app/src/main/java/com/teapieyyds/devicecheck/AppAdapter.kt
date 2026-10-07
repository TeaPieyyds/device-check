package com.teapieyyds.devicecheck

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import com.teapieyyds.devicecheck.model.AppItem
import com.teapieyyds.devicecheck.model.Light
import com.teapieyyds.devicecheck.model.LogEntry
import com.teapieyyds.devicecheck.shizuku.ShizukuShell

/**
 * 应用列表适配器。
 *
 * 每个卡片有两种按钮组：
 *  - row_unknown：还没判定 → [分享给 AI] [打开看看]
 *  - row_decision：判定阶段 → [安全](2下) [删除](3下)
 */
class AppAdapter(
    private val context: Context,
    private val items: MutableList<AppItem>,
    private val onLog: (LogEntry) -> Unit,
    private val onRemove: (AppItem) -> Unit
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

    /** 记录每个包名当前的连击进度 */
    private val safeClick = mutableMapOf<String, Int>()
    private val deleteClick = mutableMapOf<String, Int>()

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    fun replaceAll(newItems: List<AppItem>) {
        items.clear()
        items.addAll(newItems)
        safeClick.clear()
        deleteClick.clear()
        notifyDataSetChanged()
    }

    fun addItem(item: AppItem) {
        items.add(item)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_app, parent, false)
        val item = items[position]

        val dot = view.findViewById<View>(R.id.dot)
        val tvName = view.findViewById<TextView>(R.id.tv_name)
        val tvPkg = view.findViewById<TextView>(R.id.tv_pkg)
        val tvNote = view.findViewById<TextView>(R.id.tv_note)
        val rowUnknown = view.findViewById<View>(R.id.row_unknown)
        val rowDecision = view.findViewById<View>(R.id.row_decision)
        val btnShare = view.findViewById<Button>(R.id.btn_share)
        val btnOpen = view.findViewById<Button>(R.id.btn_open)
        val btnSafe = view.findViewById<Button>(R.id.btn_safe)
        val btnDelete = view.findViewById<Button>(R.id.btn_delete)

        tvName.text = if (item.isActiveAdmin) "🔐 ${item.displayName}" else item.displayName
        tvPkg.text = item.pkg

        // 状态灯 + 说明
        when (item.light) {
            Light.GREEN -> {
                dot.setBackgroundColor(Color.parseColor("#4CAF50"))
                tvNote.text = item.note ?: "正常"
                tvNote.setTextColor(Color.parseColor("#757575"))
            }
            Light.YELLOW -> {
                dot.setBackgroundColor(Color.parseColor("#FFB300"))
                tvNote.text = "我不认识这个应用，请自行判断"
                tvNote.setTextColor(Color.parseColor("#E65100"))
            }
            Light.RED -> {
                dot.setBackgroundColor(Color.parseColor("#E53935"))
                tvNote.text = item.note ?: "可疑，请谨慎处理"
                tvNote.setTextColor(Color.parseColor("#C62828"))
            }
        }

        // 按钮组切换
        if (!item.asked) {
            rowUnknown.visibility = View.VISIBLE
            rowDecision.visibility = View.GONE
        } else {
            rowUnknown.visibility = View.GONE
            rowDecision.visibility = View.VISIBLE
        }

        // 打开看看：仅当可启动
        btnOpen.visibility = if (item.launchable) View.VISIBLE else View.GONE

        // 分享给 AI
        btnShare.setOnClickListener {
            shareToAi(item)
            markAsked(item, position)
        }

        // 打开看看
        btnOpen.setOnClickListener {
            openApp(item)
            markAsked(item, position)
        }

        // 安全：点 2 下
        btnSafe.text = safeButtonText(item)
        btnSafe.setOnClickListener {
            val cur = (safeClick[item.pkg] ?: 0) + 1
            if (cur >= 2) {
                safeClick.remove(item.pkg)
                onLog(LogEntry("已标记安全：${item.pkg}", "marked safe: ${item.pkg}"))
                onRemove(item)
            } else {
                safeClick[item.pkg] = cur
                btnSafe.text = safeButtonText(item)
                // 1.5 秒未继续则复位
                btnSafe.postDelayed({
                    if (safeClick[item.pkg] == cur) {
                        safeClick.remove(item.pkg)
                        btnSafe.text = safeButtonText(item)
                    }
                }, 1500)
            }
        }

        // 删除：点 3 下
        btnDelete.text = deleteButtonText(item)
        btnDelete.setOnClickListener {
            val cur = (deleteClick[item.pkg] ?: 0) + 1
            if (cur >= 3) {
                deleteClick.remove(item.pkg)
                btnDelete.text = deleteButtonText(item)
                openUninstall(item, btnDelete)
            } else {
                deleteClick[item.pkg] = cur
                btnDelete.text = deleteButtonText(item)
                // 可见反馈：告诉用户还要点几下
                toast("再点 ${3 - cur} 次，打开系统卸载页")
                btnDelete.postDelayed({
                    if (deleteClick[item.pkg] == cur) {
                        deleteClick.remove(item.pkg)
                        btnDelete.text = deleteButtonText(item)
                    }
                }, 1800)
            }
        }

        // 重用时清理残留
        return view
    }

    private fun markAsked(item: AppItem, position: Int) {
        item.asked = true
        notifyDataSetChanged()
    }

    private fun safeButtonText(item: AppItem): String {
        val cur = safeClick[item.pkg] ?: 0
        return when (cur) {
            0 -> "安全"
            1 -> "再点 1 次"
            else -> "安全"
        }
    }

    private fun deleteButtonText(item: AppItem): String {
        val cur = deleteClick[item.pkg] ?: 0
        return when (cur) {
            0 -> "删除"
            1 -> "再点 2 次"
            2 -> "再点 1 次"
            else -> "删除"
        }
    }

    /** 系统分享：把包名+问题交给 AI 应用 */
    private fun shareToAi(item: AppItem) {
        val text = buildString {
            append("这个安卓应用包名是 ")
            append(item.pkg)
            append("。")
            if (item.label != null) append("名称显示为「${item.label}」。")
            append("请问这是什么软件？安全吗？")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, "分享给 AI 询问")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    /** 打开应用 */
    private fun openApp(item: AppItem) {
        val intent = context.packageManager.getLaunchIntentForPackage(item.pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        } else {
            onLog(LogEntry("该应用没有启动界面", "no launch intent: ${item.pkg}"))
        }
    }

    /**
     * 打开系统卸载页（**经由 Shizuku 的 shell 身份**）。
     *
     * 为什么不用普通 startActivity？
     *  - 普通 App 发 ACTION_DELETE，在 ColorOS / OriginOS 等 ROM 上会被拦，
     *    表现为「跳一下又回来」（主人遇到的就是这个）。
     *  - 用 Shizuku 以 shell 身份 `am start`，拥有 DELETE_PACKAGES 权限，
     *    系统卸载页可以正常打开。
     *
     * 保险措施：
     *  - shell 调用是阻塞的，放到后台线程执行，避免卡住 UI
     *  - 若 Shizuku 不可用 / 执行失败，自动降级为普通 startActivity
     *  - 仍失败则 Toast 提示，绝不静默
     */
    private fun openUninstall(item: AppItem, btn: View) {
        if (!ShizukuShell.hasPermission()) {
            // 没有 Shizuku 权限：退回普通方式（部分 ROM 可用）
            fallbackUninstall(item)
            return
        }

        if (btn is android.widget.Button) {
            btn.isEnabled = false
            btn.text = "处理中…"
        }

        Thread {
            val result = try {
                ShizukuShell.openUninstallPage(item.pkg)
            } catch (t: Throwable) {
                ShizukuShell.UninstallResult.Failure("执行异常：${t.message}")
            }

            (context as? android.app.Activity)?.runOnUiThread {
                if (btn is android.widget.Button) {
                    btn.isEnabled = true
                    btn.text = deleteButtonText(item)
                }
                when (result) {
                    is ShizukuShell.UninstallResult.Success -> {
                        onLog(
                            LogEntry(
                                "已打开「${item.displayName}」的系统卸载页",
                                "am start ACTION_DELETE package:${item.pkg}"
                            )
                        )
                    }
                    is ShizukuShell.UninstallResult.Failure -> {
                        onLog(
                            LogEntry(
                                "Shizuku 打开卸载页失败（${result.reason}），改用普通方式",
                                "am start failed: ${result.reason}"
                            )
                        )
                        fallbackUninstall(item)
                    }
                }
            }
        }.start()
    }

    /** 降级方案：普通 Intent（部分 ROM 可用），再失败则跳应用信息页 */
    private fun fallbackUninstall(item: AppItem) {
        // A. 普通 ACTION_DELETE
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:${item.pkg}")
            }
            context.startActivity(intent)
            onLog(LogEntry("已打开卸载页：${item.pkg}", "ACTION_DELETE ${item.pkg}"))
            return
        } catch (t: Throwable) {
            onLog(LogEntry("普通卸载页打不开，跳转应用信息页", "ACTION_DELETE failed: ${t.message}"))
        }

        // B. 应用信息页
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${item.pkg}")
            }
            context.startActivity(intent)
            onLog(
                LogEntry(
                    "已打开「${item.displayName}」的应用信息页，请在那里点「卸载」",
                    "ACTION_APPLICATION_DETAILS_SETTINGS ${item.pkg}"
                )
            )
        } catch (t: Throwable) {
            toast("无法打开卸载页，请到「设置 → 应用管理」里手动卸载")
            onLog(LogEntry("无法打开任何卸载相关页面：${item.pkg}", "all intents failed: ${t.message}"))
        }
    }

    /** 轻量 Toast，避免依赖 Activity */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}