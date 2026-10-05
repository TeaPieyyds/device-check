package com.teapieyyds.devicecheck

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 「关于」与「Shizuku 引导」相关的对话框。
 * 从 MainActivity 拆出来，保持主逻辑文件短小。
 */

/** Shizuku 官方下载地址（最新稳定版 APK） */
private const val SHIZUKU_DOWNLOAD_URL =
    "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"

/** Shizuku 安装 + 授权教程 */
private const val SHIZUKU_TUTORIAL_URL = "https://b23.tv/QwC2wz4"

/** 项目开源仓库 */
private const val ABOUT_REPO_URL = "https://github.com/TeaPieyyds/device-check"

/** 协议原文地址 */
private const val LICENSE_URL = "https://polyformproject.org/licenses/noncommercial/1.0.0"

/** 打开外部链接；失败时把链接弹出来让用户手动复制 */
internal fun MainActivity.openUrl(url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    } catch (t: Throwable) {
        Toast.makeText(
            this,
            getString(R.string.guide_link_failed) + "\n" + url,
            Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * 检查是否需要弹「引导弹窗」：
 * - 没装 Shizuku → 弹「需要安装」
 * - 装了但服务没运行 → 弹「需要启动」
 * 已授权/正常运行时不弹。
 */
internal fun MainActivity.maybeShowShizukuGuide(available: Boolean, granted: Boolean) {
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
        // 装了、服务在跑、但没授权 —— 会自动发起授权请求，让弹窗自然出现
        guideShownThisLaunch = false
    }
}

/** Shizuku 引导弹窗 */
internal fun MainActivity.showGuideDialog(title: String, message: String, showDownload: Boolean) {
    val b = AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
    if (showDownload) {
        b.setPositiveButton(R.string.guide_btn_download) { _, _ -> openUrl(SHIZUKU_DOWNLOAD_URL) }
    } else {
        b.setPositiveButton(R.string.guide_btn_ok, null)
    }
    b.setNeutralButton(R.string.guide_btn_tutorial) { _, _ -> openUrl(SHIZUKU_TUTORIAL_URL) }
    if (!showDownload) {
        // 已装未启动：额外给一个「去启动」入口
        b.setNegativeButton("去启动 Shizuku") { _, _ -> launchShizukuApp() }
    }
    b.show()
}

/** 尝试拉起 Shizuku App 主界面 */
internal fun MainActivity.launchShizukuApp() {
    try {
        val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (t: Throwable) {
        // 忽略
    }
}

/** 「关于」弹窗 */
internal fun MainActivity.showAboutDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.about_title)
        .setMessage(getString(R.string.about_body))
        .setPositiveButton(R.string.about_btn_repo) { _, _ -> openUrl(ABOUT_REPO_URL) }
        .setNeutralButton(R.string.about_btn_license) { _, _ -> showLicenseDialog() }
        .setNegativeButton(R.string.about_btn_ok, null)
        .show()
}

/** 许可协议：本地「说人话版」+ 可跳转原文 */
internal fun MainActivity.showLicenseDialog() {
    AlertDialog.Builder(this)
        .setTitle(R.string.license_title)
        .setMessage(getString(R.string.license_summary))
        .setPositiveButton(R.string.license_btn_full) { _, _ -> openUrl(LICENSE_URL) }
        .setNegativeButton(R.string.about_btn_ok, null)
        .show()
}