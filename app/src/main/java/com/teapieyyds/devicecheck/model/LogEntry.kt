package com.teapieyyds.devicecheck.model

/**
 * 一条日志。同时保存"人话"与"原始命令/输出"两个版本，
 * 切换显示模式时无需重新执行。
 */
data class LogEntry(
    /** 人话版 */
    val human: String,
    /** 原始版（命令或原始输出） */
    val raw: String
)
