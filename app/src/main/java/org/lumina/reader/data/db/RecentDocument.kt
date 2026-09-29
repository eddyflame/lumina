package org.lumina.reader.data.db

import android.net.Uri

/**
 * 最近阅读文档实体
 */
data class RecentDocument(
    val uriString: String,
    val title: String,
    val pageCount: Int,
    val lastReadPage: Int,
    val progressPercent: Float,
    val lastReadTime: Long,
    val isPinned: Boolean = false
) {
    val uri: Uri get() = Uri.parse(uriString)

    /** 格式化显示进度 */
    val progressFormatted: String
        get() = "${(progressPercent * 100).toInt()}% · 第 ${lastReadPage + 1} / $pageCount 页"

    /** 格式化显示相对时间 */
    val timeFormatted: String
        get() {
            val now = System.currentTimeMillis()
            val diff = now - lastReadTime
            return when {
                diff < 60_000 -> "刚刚"
                diff < 3600_000 -> "${diff / 60_000} 分钟前"
                diff < 86400_000 -> "${diff / 3600_000} 小时前"
                diff < 7 * 86400_000 -> "${diff / 86400_000} 天前"
                else -> {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    date.format(java.util.Date(lastReadTime))
                }
            }
        }
}
