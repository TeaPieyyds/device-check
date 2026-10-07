package com.teapieyyds.devicecheck

import android.content.Context
import org.json.JSONObject

/**
 * 已知恶意应用特征库。
 *
 * 数据来自 res/raw/threats.json，来源为公开安全分析报告（CN-SEC 等）。
 * 本库只做「本机自查」，不含样本本体，也不做任何样本分发。
 */
object ThreatDB {

    /** 一条威胁记录 */
    data class Threat(
        val pkg: String,
        val family: String,
        val level: String,
        val risk: Int,
        val type: String,
        val note: String
    )

    private val threats = mutableMapOf<String, Threat>()
    private var loaded = false

    /** 库里当前的条目数（用于界面展示"特征库 vX 共 N 条"） */
    val count: Int get() = threats.size

    fun load(context: Context) {
        if (loaded) return
        try {
            val text = context.resources.openRawResource(R.raw.threats)
                .bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val map = json.optJSONObject("threats") ?: return
            val keys = map.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val o = map.getJSONObject(key)
                threats[key] = Threat(
                    pkg = key,
                    family = o.optString("family", ""),
                    level = o.optString("level", "HIGH"),
                    risk = o.optInt("risk", 0),
                    type = o.optString("type", ""),
                    note = o.optString("note", "")
                )
            }
            loaded = true
        } catch (t: Throwable) {
            // 加载失败则库为空，不影响正常扫描
        }
    }

    /** 精确匹配包名 */
    fun match(pkg: String): Threat? = threats[pkg]

    /**
     * 生成给扫描界面用的说明文字。
     * 命中即返回「⚠ 家族 · 类型」这样的人话说明。
     */
    fun describe(t: Threat): String {
        return buildString {
            append("⚠ ").append(t.family)
            if (t.type.isNotEmpty()) append(" · ").append(t.type)
            if (t.risk > 0) append("（风险 ").append(t.risk).append("）")
        }
    }
}
