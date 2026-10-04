package com.teapieyyds.devicecheck

import android.content.Context
import org.json.JSONObject

/**
 * 白名单：前缀匹配 + 精确包名匹配。
 * 数据来自 res/raw/whitelist.json。
 */
object Whitelist {

    private val prefixes = mutableListOf<String>()
    private val packages = mutableMapOf<String, Pair<String, String>>() // pkg -> (name, note)
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        try {
            val text = context.resources.openRawResource(R.raw.whitelist)
                .bufferedReader().use { it.readText() }
            val json = JSONObject(text)

            val pArr = json.optJSONArray("prefixes")
            if (pArr != null) {
                for (i in 0 until pArr.length()) {
                    prefixes.add(pArr.getString(i))
                }
            }

            val pkgs = json.optJSONObject("packages")
            if (pkgs != null) {
                val keys = pkgs.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = pkgs.getJSONObject(key)
                    val name = obj.optString("name", key)
                    val note = obj.optString("note", "")
                    packages[key] = name to note
                }
            }
            loaded = true
        } catch (t: Throwable) {
            // 加载失败则白名单为空，所有应用都会走"不认识"流程，不至于崩溃
        }
    }

    /** 精确包名命中 */
    fun matchExact(pkg: String): Pair<String, String>? = packages[pkg]

    /** 前缀命中 */
    fun matchPrefix(pkg: String): Boolean = prefixes.any { pkg.startsWith(it) }

    /**
     * 综合匹配。
     * @return 命中则返回 (名称, 说明)，未命中返回 null
     */
    fun match(pkg: String): Pair<String, String>? {
        matchExact(pkg)?.let { return it }
        if (matchPrefix(pkg)) {
            return Pair(null, "系统或厂商自带应用，正常")
        }
        return null
    }
}