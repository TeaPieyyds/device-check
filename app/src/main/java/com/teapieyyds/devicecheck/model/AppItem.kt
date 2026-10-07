package com.teapieyyds.devicecheck.model

/**
 * 状态灯等级
 */
enum class Light {
    /** 白名单命中，正常 */
    GREEN,
    /** 未命中，需要用户判断 */
    YELLOW,
    /** 无翻译且信息可疑（预留） */
    RED
}

/**
 * 单条检查结果
 */
data class AppItem(
    /** 包名 */
    val pkg: String,
    /** 应用名（可能为 null，拿不到时用包名） */
    val label: String?,
    /** 翻译说明（人话），null 表示"我不认识" */
    val note: String?,
    /** 状态灯 */
    val light: Light,
    /** 是否可启动（有 Launcher Activity） */
    val launchable: Boolean,
    /** 安装来源（null=未知/系统预装） */
    val installer: String?,
    /** 危险权限（用于分享给 AI 时补充信息） */
    val permissions: List<String> = emptyList(),
    /** 是否为当前活跃的设备管理员 */
    val isActiveAdmin: Boolean = false
) {
    /** 展示用名称 */
    val displayName: String get() = label ?: pkg

    /** 是否已被用户判定（安全/删除）——内存态，不持久化 */
    var decision: Decision = Decision.NONE

    /** 是否已点过"分享/打开"（用于切换按钮组） */
    var asked: Boolean = false
}

enum class Decision {
    NONE,
    SAFE,
    DELETE
}
