package com.flowai.communication.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/**
 * 剪贴板写入的唯一入口（规格要求"复制函数单独封装"）。
 *
 * 所有需要复制的调用方都走这里：个别 OEM ROM 在特殊场景下会对
 * `setPrimaryClip` 抛 `SecurityException`，统一 `runCatching` 兜住，
 * 崩溃不会外溢；返回 `false` 时由调用方给出替代文案。
 * [label] 用于系统剪贴板 UI 的条目名称（Android 13+ 会显示来源应用）。
 */
object ClipboardCopier {

    private const val TAG = "FlowAI"

    /**
     * 把 [text] 复制到系统剪贴板。
     *
     * @return true 表示写入成功；false 表示系统层失败（已记日志，未抛出）。
     */
    fun copy(context: Context, label: String, text: String): Boolean = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrElse { e ->
        Log.w(TAG, "clipboard copy failed", e)
        false
    }
}
