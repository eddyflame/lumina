package org.lumina.reader.data.db

import android.content.Context
import android.net.Uri
import org.lumina.reader.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** 格式化显示进度 (支持 Context 本地化) */
    fun getProgressFormatted(context: Context): String {
        return context.getString(
            R.string.recent_progress_format,
            (progressPercent * 100).toInt(),
            lastReadPage + 1,
            pageCount
        )
    }

    /** 格式化显示相对时间 (支持 Context 本地化) */
    fun getTimeFormatted(context: Context): String {
        val now = System.currentTimeMillis()
        val diff = now - lastReadTime
        return when {
            diff < 60_000 -> context.getString(R.string.time_just_now)
            diff < 3600_000 -> context.getString(R.string.time_minutes_ago, (diff / 60_000).toInt())
            diff < 86400_000 -> context.getString(R.string.time_hours_ago, (diff / 3600_000).toInt())
            diff < 7 * 86400_000 -> context.getString(R.string.time_days_ago, (diff / 86400_000).toInt())
            else -> {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                date.format(Date(lastReadTime))
            }
        }
    }

    /** 默认格式化进度 (自适应当前系统 Locale) */
    val progressFormatted: String
        get() {
            val isZh = Locale.getDefault().language.startsWith("zh")
            return if (isZh) {
                "${(progressPercent * 100).toInt()}% · 第 ${lastReadPage + 1} / $pageCount 页"
            } else {
                "${(progressPercent * 100).toInt()}% · Page ${lastReadPage + 1} / $pageCount"
            }
        }

    /** 默认格式化相对时间 (自适应当前系统 Locale) */
    val timeFormatted: String
        get() {
            val now = System.currentTimeMillis()
            val diff = now - lastReadTime
            val isZh = Locale.getDefault().language.startsWith("zh")
            return when {
                diff < 60_000 -> if (isZh) "刚刚" else "Just now"
                diff < 3600_000 -> if (isZh) "${diff / 60_000} 分钟前" else "${diff / 60_000} min ago"
                diff < 86400_000 -> if (isZh) "${diff / 3600_000} 小时前" else "${diff / 3600_000} h ago"
                diff < 7 * 86400_000 -> if (isZh) "${diff / 86400_000} 天前" else "${diff / 86400_000} d ago"
                else -> {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    date.format(Date(lastReadTime))
                }
            }
        }
}
